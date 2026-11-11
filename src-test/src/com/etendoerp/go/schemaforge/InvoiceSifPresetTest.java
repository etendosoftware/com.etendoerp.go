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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Repro tests of ETP-6179: the invoices created AND completed in one call (from an order, a
 * shipment, a receipt or a return) never got their SIF fields.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceSifPreset
 */
public class InvoiceSifPresetTest {

  private static final String ORG_ID = "org-6179";

  private static InvoiceSifPreset.Facts salesFacts(boolean rectificative, String docInvType) {
    return new InvoiceSifPreset.Facts(true, rectificative, true, true, true, docInvType, null,
        false, false, null, null, "2026-11-11", null, null);
  }

  /**
   * A rectificative generated from a return used to complete as F1 with no SII motive and no
   * TicketBAI code. With nothing sent, every active system now gets R4.
   */
  @Test
  public void aRectificativeWithNothingSentGetsR4InEveryActiveSystem() {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"), null);

    assertEquals("R4", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertEquals("R4", values.get(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
    assertEquals("R4", values.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
  }

  /**
   * A document type configured F2 used to yield F1, because the entity default made the
   * completion's COALESCE(invoice, docType) copy a no-op.
   */
  @Test
  public void aNormalInvoiceTakesTheVerifactuTypeOfItsDocumentType() {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(false, "F2"), null);

    assertEquals("F2", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertFalse(values.containsKey(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
  }

  /** End to end on the invoice: the rectificative is written R4 before the completion. */
  @Test
  public void applyBeforeCompletionWritesR4OnARectificativeInvoice() throws Exception {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(true);
    DocumentType docType = mock(DocumentType.class);
    when(docType.getEtvfacInvType()).thenReturn("F1");
    when(invoice.getTransactionDocument()).thenReturn(docType);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<RectificativeSupport> rectifMock = Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class);
         MockedStatic<RectificationFiscalCodeGuard> guardMock =
             Mockito.mockStatic(RectificationFiscalCodeGuard.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      rectifMock.when(() -> RectificativeSupport.isRectificative(any())).thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
          .thenReturn(false);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.verifactuConfigActive(ORG_ID))
          .thenReturn(true);
      guardMock.when(() -> RectificationFiscalCodeGuard.evaluate(invoice)).thenReturn(null);

      InvoiceSifPreset.applyBeforeCompletion(invoice, new JSONObject());

      verify(invoice).set(Invoice.PROPERTY_ETVFACINVTYPE, "R4");
      verify(invoice).set(Invoice.PROPERTY_TBAIREVERSEINVOICECODE, "R4");
      guardMock.verify(() -> RectificationFiscalCodeGuard.evaluate(invoice));
    }
  }
}
