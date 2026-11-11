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
 * Unit tests for {@link SalesRectificativeFiscalDefaults#computeAndSet}/{@code #apply}.
 *
 * <p>This class merges two test generations (resolved 2026-11-07, ETP-5745/ETP-5747 merge):
 * the harness below (the {@code @Before}/{@code @After} DB-mock setup, {@code docType}/
 * {@code draftInvoice} helpers) is ETP-5747's original, kept because it exercises the REAL
 * {@code tbaiConfigActive}/{@code verifactuConfigActive} SQL plumbing end to end, including the
 * failure paths — more thorough than stubbing those lookups away. Every test that assumed
 * ETP-5747's original (and since-reverted) behavior — copying the document type's own configured
 * {@code em_etvfac_inv_type} onto the invoice, or auto-picking TicketBAI's
 * {@code tbaiReverseinvoicecode} to {@code "R4"} — was discarded or adapted: that behavior is
 * exactly the ETP-5745 CP-14/CP-20 bug this class exists to NOT reproduce (a "Factura
 * rectificativa" DocType configured with "R1"/"R4" must never silently stamp every new
 * rectificative invoice with it, even when the org doesn't use that fiscal system at all).
 *
 * @covers com.etendoerp.go.schemaforge.SalesRectificativeFiscalDefaults
 */
public class SalesRectificativeFiscalDefaultsTest {

  private static final String INVOICE_ID = "inv-5745";
  private static final String ORG_ID = "org-5745";

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
    when(conn.prepareStatement(InvoiceCalloutHelper.TBAI_ACTIVE_SQL))
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

  /** A draft sales invoice with the AD defaults: no TicketBAI flag, VERI*FACTU {@code F1}. */
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

  // ---------------------------------------------------------------------------
  // TicketBAI
  // ---------------------------------------------------------------------------

  /** TicketBAI's structural fields are applied; the code is deliberately NEVER touched (CP-14). */
  @Test
  public void tbaiOrgRectificativeDraftGetsReverseFlagAndTypeOnly() throws Exception {
    configActive(tbaiStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice).setTbaiIsreverseinvoice(true);
    verify(invoice).setTbaiReverseinvoicetype("I");
    verify(invoice, never()).setTbaiReverseinvoicecode(anyString());
    assertEquals(Boolean.TRUE, applied.get("tbaiIsreverseinvoice"));
    assertEquals("I", applied.get("tbaiReverseinvoicetype"));
    assertFalse(applied.containsKey("tbaiReverseinvoicecode"));
    assertEquals(2, applied.size());
  }

  @Test
  public void rectificativeInvoice_tbaiInactive_tbaiFieldsNeverTouched() throws Exception {
    // tbaiStatement inactive by default (setUp)
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));

    SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice, never()).setTbaiIsreverseinvoice(anyBoolean());
    verify(invoice, never()).setTbaiReverseinvoicetype(anyString());
  }

  /** The TicketBAI reverse flag is ENFORCED: N with type and code already chosen → only the flag moves. */
  @Test
  public void tbaiReverseFlagIsEnforcedEvenWhenTypeAndCodeAreChosen() throws Exception {
    configActive(tbaiStatement, true);
    Invoice invoice = draftInvoice(docType(true, null, null));
    when(invoice.getTbaiReverseinvoicetype()).thenReturn("S");
    when(invoice.getTbaiReverseinvoicecode()).thenReturn("R1");

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice).setTbaiIsreverseinvoice(true);
    verify(invoice, never()).setTbaiReverseinvoicetype(anyString());
    verify(invoice, never()).setTbaiReverseinvoicecode(anyString());
    assertEquals(1, applied.size());
    assertEquals(Boolean.TRUE, applied.get("tbaiIsreverseinvoice"));
  }

  // ---------------------------------------------------------------------------
  // VERI*FACTU — ETP-5745 CP-20: etvfacInvType is NEVER copied from the DocType
  // ---------------------------------------------------------------------------

  /**
   * The core CP-20 repro: a DocType configured with VERI*FACTU code "R1" must NEVER reach the
   * invoice, even when VERI*FACTU is active for the org — only TicketBAI's structural fields are
   * applied, and the reverse type is never defaulted either (the invoice's own code is still the
   * non-rectificative AD default).
   */
  @Test
  public void rectificativeInvoice_docTypeConfiguredWithR1_neverCopiesItToEtvfacInvType()
      throws Exception {
    configActive(tbaiStatement, true);
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, "R1", null));

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice, never()).setEtvfacInvType(anyString());
    verify(invoice, never()).setEtvfacReverseinvtype(anyString());
    assertFalse(applied.containsKey(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE));
    // TicketBAI's own structural fields are unaffected by this fix.
    verify(invoice).setTbaiIsreverseinvoice(true);
    verify(invoice).setTbaiReverseinvoicetype("I");
  }

  @Test
  public void rectificativeInvoice_verifactuInactive_reverseTypeNeverTouchedEvenWithRectifCode()
      throws Exception {
    // verifactuStatement inactive by default (setUp)
    Invoice invoice = draftInvoice(docType(true, null, null));
    when(invoice.getEtvfacInvType()).thenReturn("R1");

    SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice, never()).setEtvfacReverseinvtype(anyString());
  }

  /** An R code the user picked (R4) is kept even if the doc type says R1 — not copied, not replaced. */
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

  /** A user-picked R4 with a blank reverse type: R4 is kept, the reverse type is filled. */
  @Test
  public void verifactuChosenRectificativeCodeGetsItsMissingReverseType() throws Exception {
    configActive(verifactuStatement, true);
    Invoice fromDocType = draftInvoice(docType(true, "R1", "S"));
    when(fromDocType.getEtvfacInvType()).thenReturn("R4");

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(fromDocType);

    verify(fromDocType, never()).setEtvfacInvType(anyString());
    verify(fromDocType).setEtvfacReverseinvtype("S");
    assertEquals(1, applied.size());
    assertEquals("S", applied.get("etvfacReverseinvtype"));

    Invoice fallback = draftInvoice(docType(true, "R1", null));
    when(fallback.getEtvfacInvType()).thenReturn("R4");
    when(fallback.getEtvfacReverseinvtype()).thenReturn("  ");

    SalesRectificativeFiscalDefaults.computeAndSet(fallback);

    verify(fallback, never()).setEtvfacInvType(anyString());
    verify(fallback).setEtvfacReverseinvtype("I");
  }

  // ---------------------------------------------------------------------------
  // Eligibility / cross-cutting
  // ---------------------------------------------------------------------------

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

  /** A draft status alone is not enough: a processed invoice is never touched. */
  @Test
  public void processedDraftIsUntouched() throws Exception {
    configActive(tbaiStatement, true);
    configActive(verifactuStatement, true);
    Invoice invoice = draftInvoice(docType(true, "R1", null));
    when(invoice.isProcessed()).thenReturn(true);

    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(invoice).isEmpty());
    verify(invoice, never()).setTbaiIsreverseinvoice(anyBoolean());
    verify(invoice, never()).setEtvfacInvType(anyString());
  }

  /**
   * A failing config lookup answers "not configured" for THAT system only — the other system,
   * when active, is still applied on the same invoice.
   */
  @Test
  public void failingConfigLookupAppliesNothingForThatSystem() throws Exception {
    when(tbaiStatement.executeQuery()).thenThrow(new SQLException("tbai down"));
    configActive(verifactuStatement, true);
    assertFalse(SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID));
    Invoice invoice = draftInvoice(docType(true, null, null));
    when(invoice.getEtvfacInvType()).thenReturn("R1"); // the user's own manual pick
    when(invoice.getEtvfacReverseinvtype()).thenReturn(null);

    Map<String, Object> applied = SalesRectificativeFiscalDefaults.computeAndSet(invoice);

    verify(invoice, never()).setTbaiIsreverseinvoice(anyBoolean());
    assertEquals("I", applied.get("etvfacReverseinvtype"));

    when(verifactuStatement.executeQuery()).thenThrow(new SQLException("vf down"));
    assertFalse(SalesRectificativeFiscalDefaults.configExists(
        SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL, ORG_ID));
    Invoice second = draftInvoice(docType(true, null, null));
    when(second.getEtvfacInvType()).thenReturn("R1");
    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(second).isEmpty());
  }

  /** No organization, or no invoice found: nothing applied, nothing flushed. */
  @Test
  public void missingOrganizationOrInvoiceAppliesNothing() throws Exception {
    configActive(tbaiStatement, true);
    configActive(verifactuStatement, true);
    Invoice orphan = draftInvoice(docType(true, "R1", null));
    when(orphan.getOrganization()).thenReturn(null);
    assertTrue(SalesRectificativeFiscalDefaults.computeAndSet(orphan).isEmpty());
    verify(orphan, never()).setTbaiIsreverseinvoice(anyBoolean());

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      when(dal.get(Invoice.class, "missing")).thenReturn(null);

      assertTrue(SalesRectificativeFiscalDefaults.apply("missing").isEmpty());
      verify(dal, never()).flush();
    }
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

      assertEquals(Boolean.TRUE, applied.get("tbaiIsreverseinvoice"));
      assertEquals("I", applied.get("tbaiReverseinvoicetype"));
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
