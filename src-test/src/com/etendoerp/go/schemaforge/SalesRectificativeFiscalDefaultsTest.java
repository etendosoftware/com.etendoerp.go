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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link SalesRectificativeFiscalDefaults#computeAndSet} (ETP-5745 CP-20):
 * TicketBAI's structural fields are still auto-applied (gated by {@code tbaiConfigActive}), but
 * VERI*FACTU's {@code etvfacInvType} is NEVER copied from the DocType's own configured code any
 * more — that was the original bug (a "Factura rectificativa" DocType configured with "R1"
 * silently stamped every new rectificative invoice with it, even when the org didn't use
 * VERI*FACTU at all).
 *
 * @covers com.etendoerp.go.schemaforge.SalesRectificativeFiscalDefaults
 */
public class SalesRectificativeFiscalDefaultsTest {

  private static final String ORG_ID = "org-5745";
  private static final String DRAFT = "DR";

  private Invoice eligibleInvoice(DocumentType docType) {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(true);
    when(invoice.getDocumentStatus()).thenReturn(DRAFT);
    when(invoice.isProcessed()).thenReturn(false);
    when(invoice.getTransactionDocument()).thenReturn(docType);
    return invoice;
  }

  private Map<String, Object> computeWith(Invoice invoice, boolean isRectificative,
      boolean tbaiActive, boolean verifactuActive) {
    try (MockedStatic<RectificativeSupport> rectifMock = Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock = Mockito.mockStatic(
             SalesRectificativeFiscalDefaults.class, Mockito.CALLS_REAL_METHODS)) {
      rectifMock.when(() -> RectificativeSupport.isRectificative(invoice.getTransactionDocument()))
          .thenReturn(isRectificative);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
          .thenReturn(tbaiActive);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.configExists(
              SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL, ORG_ID))
          .thenReturn(verifactuActive);

      return SalesRectificativeFiscalDefaults.computeAndSet(invoice);
    }
  }

  @Test
  public void nonRectificativeInvoice_isANoOp() {
    Invoice invoice = eligibleInvoice(mock(DocumentType.class));

    Map<String, Object> applied = computeWith(invoice, false, true, true);

    assertTrue(applied.isEmpty());
    verify(invoice, never()).setEtvfacInvType(anyString());
    verify(invoice, never()).setTbaiIsreverseinvoice(org.mockito.ArgumentMatchers.anyBoolean());
  }

  /**
   * ETP-5745 CP-20: the core repro — a DocType configured with VERI*FACTU code "R1" must NEVER
   * reach the invoice. Only TicketBAI's structural fields are applied.
   */
  @Test
  public void rectificativeInvoice_docTypeConfiguredWithR1_neverCopiesItToEtvfacInvType() {
    DocumentType docType = mock(DocumentType.class);
    when(docType.getEtvfacInvType()).thenReturn("R1"); // configured on the DocType itself
    Invoice invoice = eligibleInvoice(docType);
    when(invoice.getEtvfacInvType()).thenReturn(null);
    when(invoice.isTbaiIsreverseinvoice()).thenReturn(false);
    when(invoice.getTbaiReverseinvoicetype()).thenReturn(null);

    Map<String, Object> applied = computeWith(invoice, true, true, true);

    verify(invoice, never()).setEtvfacInvType(anyString());
    assertFalse(applied.containsKey(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE));
    // TicketBAI's own structural fields are unaffected by this fix.
    verify(invoice).setTbaiIsreverseinvoice(true);
    verify(invoice).setTbaiReverseinvoicetype("I");
  }

  @Test
  public void rectificativeInvoice_tbaiInactive_tbaiFieldsNeverTouched() {
    Invoice invoice = eligibleInvoice(mock(DocumentType.class));

    computeWith(invoice, true, false, true);

    verify(invoice, never()).setTbaiIsreverseinvoice(org.mockito.ArgumentMatchers.anyBoolean());
    verify(invoice, never()).setTbaiReverseinvoicetype(anyString());
  }

  /**
   * The reverse type IS still defaulted — but only once the user has ALREADY picked an R1-R5
   * code by hand (never auto-picked by this class, see the class javadoc's "What it does not do").
   */
  @Test
  public void rectificativeInvoice_userAlreadyPickedRectifCode_defaultsReverseType() {
    DocumentType docType = mock(DocumentType.class);
    when(docType.getEtvfacReverseInvType()).thenReturn(null); // DocType has none configured
    Invoice invoice = eligibleInvoice(docType);
    when(invoice.getEtvfacInvType()).thenReturn("R2"); // the user's own manual choice
    when(invoice.getEtvfacReverseinvtype()).thenReturn(null);

    computeWith(invoice, true, false, true);

    // Falls back to "I" (Por diferencias) when the DocType has no reverse type configured either.
    verify(invoice).setEtvfacReverseinvtype("I");
  }

  @Test
  public void rectificativeInvoice_etvfacInvTypeStillNonRectificative_reverseTypeNeverTouched() {
    Invoice invoice = eligibleInvoice(mock(DocumentType.class));
    when(invoice.getEtvfacInvType()).thenReturn("F1"); // still the generic default, not R1-R5

    computeWith(invoice, true, false, true);

    verify(invoice, never()).setEtvfacReverseinvtype(anyString());
  }

  @Test
  public void rectificativeInvoice_verifactuInactive_reverseTypeNeverTouchedEvenWithRectifCode() {
    Invoice invoice = eligibleInvoice(mock(DocumentType.class));
    when(invoice.getEtvfacInvType()).thenReturn("R1");

    computeWith(invoice, true, true, false);

    verify(invoice, never()).setEtvfacReverseinvtype(anyString());
  }
}
