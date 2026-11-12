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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType} and
 * {@link InvoiceCalloutHelper#applyVerifactuInvTypeFromDocType}.
 *
 * <p>Covers ETP-5229 (the {@code tbaiReverseinvoicecode} sales-only guard, autofilled/cleared for
 * SALES invoices only — {@code c_doctype.issotrx = 'Y'} — mirroring the existing
 * {@code tbaiIsreverseinvoice}/{@code tbaiReverseinvoicetype} conditional-set/clear pattern, while
 * remaining untouched for purchase invoices), ETP-5745 CP-14 (TicketBAI's reverse-invoice code is
 * only ever CLEARED on revert, never auto-picked to {@code "R4"}), CP-18 (SII's
 * {@code aeatsiiClaveTipo}/{@code aeatsiiTipoRectif}/{@code aeatsiiMotivoRectif} set on becoming
 * rectificative and reset on revert), CP-20/CP-22 (VERI*FACTU's {@code etvfacInvType} cleared to
 * {@code ""} on becoming rectificative — never copied from the DocType's own configured code —
 * and reset to {@code "F1"} on revert), CP-24/CP-25 (SII's and TicketBAI's structural fields are
 * only ever touched when the organization is actually enrolled in that fiscal system), and
 * {@link InvoiceCalloutHelper#isTbaiConfigActive} (ETP-5747), the TicketBAI-config lookup shared
 * by the header enrichment and the fiscal defaults.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceCalloutHelper
 */
public class InvoiceCalloutHelperTest {

  private static final String ORG_ID = "org-1";

  @After
  public void tearDown() {
    RectificativeSupport.setColumnPresentForTests(null);
  }

  private JSONObject requestBodyFor(String docTypeId) throws Exception {
    return new JSONObject()
        .put(AbstractInvoiceHeaderHandler.FIELD_VALUE, docTypeId);
  }

  private void mockDocType(MockedStatic<OBDal> dalMock, boolean isRectificative, boolean isSotrx)
      throws Exception {
    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(isRectificative ? "Y" : "N");
    when(rs.getString(2)).thenReturn(isSotrx ? "Y" : "N");
  }

  /** A single-column variant for {@code applyVerifactuInvTypeFromDocType}'s own query. */
  private void mockDocTypeSingleColumn(MockedStatic<OBDal> dalMock, boolean isRectificative)
      throws Exception {
    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(isRectificative ? "Y" : "N");
  }

  private void mockCurrentOrg(MockedStatic<OBContext> ctxMock) {
    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  private void mockConfigActive(MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock,
      boolean tbaiActive, boolean siiActive) {
    defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
        .thenReturn(tbaiActive);
    defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
        .thenReturn(siiActive);
  }

  // ---------------------------------------------------------------------------
  // TicketBAI — ETP-5229 / CP-14 / CP-25
  // ---------------------------------------------------------------------------

  @Test
  public void rectificativeSalesInvoice_tbaiActive_setsStructuralFieldsOnlyNeverTheCode()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, true, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, true, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-1"), updates);

      assertEquals("Y", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("I", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      // ETP-5745 CP-14: never auto-picked ("R4" or otherwise) on becoming rectificative — the
      // user must choose it manually. Only ever cleared on revert (see the next test).
      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }

  @Test
  public void nonRectificativeSalesInvoice_tbaiActive_clearsAllThreeTbaiFields() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, false, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, true, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-2"), updates);

      assertEquals("N", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("", updates.getJSONObject("tbaiReverseinvoicecode")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  @Test
  public void rectificativePurchaseInvoice_tbaiActive_reverseInvoiceCodeNeverTouched()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, true, false);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, true, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-3"), updates);

      // Sibling fields are pre-existing, direction-agnostic behavior — untouched by this fix.
      assertEquals("Y", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("I", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      // Sales-only field: never set for a purchase doc type, rectificative or not.
      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }

  @Test
  public void nonRectificativePurchaseInvoice_tbaiActive_reverseInvoiceCodeNeverTouched()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, false, false);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, true, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-4"), updates);

      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }

  /**
   * ETP-5745 CP-25: an organization with NO active TicketBAI configuration must not have ANY of
   * the three TicketBAI fields touched — becoming rectificative or not.
   */
  @Test
  public void rectificativeSalesInvoice_tbaiInactive_noTbaiFieldTouchedAtAll() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, true, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, false, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-5"), updates);

      assertFalse(updates.has("tbaiIsreverseinvoice"));
      assertFalse(updates.has("tbaiReverseinvoicetype"));
      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }

  // ---------------------------------------------------------------------------
  // SII — ETP-5745 CP-18 / CP-24
  // ---------------------------------------------------------------------------

  @Test
  public void rectificativeSalesInvoice_siiActive_setsClaveTipoAndTipoRectifOnly()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, true, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, false, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-6"), updates);

      assertEquals("R", updates.getJSONObject("aeatsiiClaveTipo")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("I", updates.getJSONObject("aeatsiiTipoRectif")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      // Never auto-picked on becoming rectificative — only ever cleared on revert.
      assertFalse(updates.has("aeatsiiMotivoRectif"));
    }
  }

  @Test
  public void nonRectificativeSalesInvoice_siiActive_resetsAllThreeSiiFields() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, false, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, false, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-7"), updates);

      assertEquals("F1", updates.getJSONObject("aeatsiiClaveTipo")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("", updates.getJSONObject("aeatsiiTipoRectif")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      // ETP-5745 CP-18: a value the user typed by hand must not stay stuck once it no longer
      // applies — cleanup, not a guess.
      assertEquals("", updates.getJSONObject("aeatsiiMotivoRectif")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  /**
   * ETP-5745 CP-24: the real bug this ticket fixed — an org that never enrolled in SII must have
   * NONE of its fields touched, so {@code aeatsiiClaveTipo} never becomes {@code "R"} and the
   * {@code EM_AEATSII_C_INV_RECT_CHK} DB check constraint (which fires on {@code ClaveTipo='R'}
   * alone, with zero awareness of whether the org even uses SII) is never reached.
   */
  @Test
  public void rectificativeSalesInvoice_siiInactive_noSiiFieldTouchedAtAll() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocType(dalMock, true, true);
      mockCurrentOrg(ctxMock);
      mockConfigActive(defaultsMock, false, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-8"), updates);

      assertFalse(updates.has("aeatsiiClaveTipo"));
      assertFalse(updates.has("aeatsiiTipoRectif"));
      assertFalse(updates.has("aeatsiiMotivoRectif"));
    }
  }

  // ---------------------------------------------------------------------------
  // VERI*FACTU — ETP-5745 CP-20 / CP-22
  // ---------------------------------------------------------------------------

  private void mockVerifactuConfigActive(
      MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock, boolean verifactuActive) {
    defaultsMock.when(() -> SalesRectificativeFiscalDefaults.verifactuConfigActive(ORG_ID))
        .thenReturn(verifactuActive);
  }

  /**
   * ETP-5745 CP-20: no longer copies the DocType's OWN configured {@code em_etvfac_inv_type}
   * (the original bug — a "Factura rectificativa" DocType configured with "R1" silently stamped
   * every new rectificative invoice with it). CP-22: now ACTIVELY CLEARS the field to {@code ""}
   * instead of leaving it untouched, giving the same visual "needs your attention" signal
   * SII/TicketBAI already give.
   */
  @Test
  public void applyVerifactuInvTypeFromDocType_becomingRectificative_clearsToEmpty()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocTypeSingleColumn(dalMock, true);
      mockCurrentOrg(ctxMock);
      mockVerifactuConfigActive(defaultsMock, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyVerifactuInvTypeFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-9"), updates);

      assertEquals("", updates.getJSONObject(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE)
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  @Test
  public void applyVerifactuInvTypeFromDocType_nonRectificative_resetsToF1() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockDocTypeSingleColumn(dalMock, false);
      mockCurrentOrg(ctxMock);
      mockVerifactuConfigActive(defaultsMock, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyVerifactuInvTypeFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-10"), updates);

      assertEquals("F1", updates.getJSONObject(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE)
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  /**
   * ETP-5745 Warning 1 (review fix): an org with NO active VERI*FACTU configuration must not get
   * {@code etvfacInvType} written on every document-type change either — aligned with
   * {@code applyRectificativeFieldsFromDocType}'s own SII/TicketBAI gates, which already skip
   * their fields for an inactive system. Before this fix the method only checked
   * {@code isColumnPresent()}, never whether the org actually uses VERI*FACTU.
   */
  @Test
  public void applyVerifactuInvTypeFromDocType_verifactuInactive_doesNotTouchUpdates()
      throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      mockCurrentOrg(ctxMock);
      mockVerifactuConfigActive(defaultsMock, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyVerifactuInvTypeFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-11"), updates);

      assertFalse(updates.has(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE));
      dalMock.verifyNoInteractions();
    }
  }

  // ── isTbaiConfigActive — the one TicketBAI-config lookup (ETP-5747) ─────────

  @Test
  public void isTbaiConfigActive_blankOrg_falseWithoutQuerying() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      assertFalse(InvoiceCalloutHelper.isTbaiConfigActive(null));
      assertFalse(InvoiceCalloutHelper.isTbaiConfigActive(""));
      dalMock.verifyNoInteractions();
    }
  }

  @Test
  public void isTbaiConfigActive_answersWhetherTheOrgHasAnActiveConfig() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(InvoiceCalloutHelper.TBAI_ACTIVE_SQL)).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);

      org.junit.Assert.assertTrue(InvoiceCalloutHelper.isTbaiConfigActive("org-tbai"));
      assertFalse(InvoiceCalloutHelper.isTbaiConfigActive("org-tbai"));
      Mockito.verify(ps, Mockito.times(2)).setString(1, "org-tbai");
    }
  }
}
