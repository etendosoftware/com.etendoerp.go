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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link RectificationFiscalCodeGuard} (ETP-5745 CP-13/CP-18/CP-20/CP-24/CP-26):
 * completing a rectificative SALES invoice is refused, naming the exact active fiscal system(s)
 * still missing their own rectification code/motive; SII's structural fields are corrected (but
 * never the motivo); and a NON-rectificative invoice with a stray VERI*FACTU rectification code
 * BLOCKS completion instead of being silently corrected.
 *
 * @covers com.etendoerp.go.schemaforge.RectificationFiscalCodeGuard
 */
public class RectificationFiscalCodeGuardTest {

  private static final String INVOICE_ID = "INV-5745";
  private static final String ORG_ID = "org-5745";

  private static NeoContext completeAction(String recordId) throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  private Invoice baseInvoice() {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(true);
    when(invoice.getTransactionDocument()).thenReturn(mock(DocumentType.class));
    return invoice;
  }

  private NeoResponse runGuard(Invoice invoice, boolean isRectificative, boolean siiActive,
      boolean tbaiActive, boolean verifactuActive) throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<RectificativeSupport> rectifMock = Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);

      rectifMock.when(() -> RectificativeSupport.isRectificative(any()))
          .thenReturn(isRectificative);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
          .thenReturn(siiActive);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
          .thenReturn(tbaiActive);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.configExists(
              SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL, ORG_ID))
          .thenReturn(verifactuActive);

      return RectificationFiscalCodeGuard.checkAndApply(completeAction(INVOICE_ID));
    }
  }

  // ---------------------------------------------------------------------------
  // gating — only a documentAction=CO completion of a SALES invoice is evaluated
  // ---------------------------------------------------------------------------

  @Test
  public void aNonCompletionPatchIsNeverEvaluated() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "RE");
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).httpMethod("PATCH").recordId(INVOICE_ID)
        .requestBody(body).build();

    assertNull(RectificationFiscalCodeGuard.checkAndApply(context));
  }

  @Test
  public void aPurchaseInvoiceIsNeverEvaluated() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.isSalesTransaction()).thenReturn(false);

    // Even a rectificative purchase invoice with every field missing must not be blocked —
    // purchase-side corrective support isn't implemented (ETP-5745 CP-10 precedent).
    assertNull(runGuard(invoice, true, true, true, true));
  }

  // ---------------------------------------------------------------------------
  // missing-systems rejection — dynamic, naming only the ACTIVE systems still missing their code
  // ---------------------------------------------------------------------------

  @Test
  public void rectificativeInvoice_siiAndTbaiActiveBothMissing_rejectsNamingBoth() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn(null);
    when(invoice.getTbaiReverseinvoicecode()).thenReturn(null);

    NeoResponse response = runGuard(invoice, true, true, true, false);

    assertNotNull(response);
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(RectificationFiscalCodeGuard.MSG_RECTIFICATION_CODE_REQUIRED,
        body.getJSONArray("messageKeys").getString(0));
    JSONArray systems = body.getJSONObject("messageParams").getJSONArray("systems");
    assertEquals(2, systems.length());
    assertEquals("SII", systems.getString(0));
    assertEquals("TicketBAI", systems.getString(1));
    assertTrue(body.getString("message").contains("SII"));
    assertTrue(body.getString("message").contains("TicketBAI"));
    // Never corrects anything on a rejection.
    verify(invoice, never()).setAeatsiiClaveTipo(anyString());
  }

  @Test
  public void rectificativeInvoice_onlyVerifactuMissing_rejectsNamingOnlyVerifactu()
      throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn("R1");
    when(invoice.getTbaiReverseinvoicecode()).thenReturn("R4");
    when(invoice.getEtvfacInvType()).thenReturn("F1");

    NeoResponse response = runGuard(invoice, true, true, true, true);

    assertNotNull(response);
    JSONArray systems = response.getBody().getJSONObject("messageParams").getJSONArray("systems");
    assertEquals(1, systems.length());
    assertEquals("Verifactu", systems.getString(0));
  }

  // ---------------------------------------------------------------------------
  // CP-24/CP-25 — gating by "is this system active for the organization"
  // ---------------------------------------------------------------------------

  /**
   * ETP-5745 CP-24: an org with NO active SII configuration must never be asked for a Motivo —
   * the guard must not even consider SII "missing", whatever the field holds.
   */
  @Test
  public void rectificativeInvoice_siiInactive_neverBlocksOnMissingMotivo() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn(null);

    assertNull(runGuard(invoice, true, false, false, false));
  }

  @Test
  public void rectificativeInvoice_tbaiInactive_neverBlocksOnMissingCode() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getTbaiReverseinvoicecode()).thenReturn(null);

    assertNull(runGuard(invoice, true, false, false, false));
  }

  @Test
  public void rectificativeInvoice_verifactuInactive_neverBlocksOnNonRectificativeCode()
      throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getEtvfacInvType()).thenReturn("F1");

    assertNull(runGuard(invoice, true, false, false, false));
  }

  // ---------------------------------------------------------------------------
  // structural correction — SII's own claveTipo/tipoRectif, only when SII is active
  // ---------------------------------------------------------------------------

  @Test
  public void rectificativeInvoice_allPresentSiiActive_correctsClaveTipoAndTipoRectif()
      throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn("R1");
    when(invoice.getAeatsiiClaveTipo()).thenReturn("F1"); // stale
    when(invoice.getAeatsiiTipoRectif()).thenReturn(null);

    assertNull(runGuard(invoice, true, true, false, false));

    verify(invoice).setAeatsiiClaveTipo("R");
    verify(invoice).setAeatsiiTipoRectif("I");
  }

  /**
   * ETP-5745 CP-24: the structural correction itself must ALSO be gated — an org with no SII
   * configuration must never have {@code aeatsiiClaveTipo} stamped "R" (which is exactly what
   * feeds the {@code EM_AEATSII_C_INV_RECT_CHK} DB check constraint).
   */
  @Test
  public void rectificativeInvoice_siiInactive_neverCorrectsClaveTipo() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiClaveTipo()).thenReturn("F1");

    assertNull(runGuard(invoice, true, false, false, false));

    verify(invoice, never()).setAeatsiiClaveTipo(anyString());
    verify(invoice, never()).setAeatsiiTipoRectif(anyString());
  }

  // ---------------------------------------------------------------------------
  // CP-26 — the reverse direction BLOCKS, it no longer silently clears
  // ---------------------------------------------------------------------------

  @Test
  public void nonRectificativeInvoice_strayVerifactuRectifCode_blocksCompletion() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getEtvfacInvType()).thenReturn("R1");

    NeoResponse response = runGuard(invoice, false, true, true, true);

    assertNotNull("a non-rectificative invoice with a stray R1-R5 Verifactu code must block",
        response);
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(RectificationFiscalCodeGuard.MSG_VERIFACTU_INV_TYPE_NOT_RECTIFICATIVE,
        body.getJSONArray("messageKeys").getString(0));
    // ETP-5745 CP-26: blocks, does NOT silently correct it — the old CP-20 behavior.
    verify(invoice, never()).setEtvfacInvType(anyString());
    verify(invoice, never()).setAeatsiiClaveTipo(anyString());
  }

  @Test
  public void nonRectificativeInvoice_normalVerifactuType_isANoOp() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getEtvfacInvType()).thenReturn("F1");

    assertNull(runGuard(invoice, false, true, true, true));

    verify(invoice, never()).setEtvfacInvType(anyString());
  }

  @Test
  public void nonRectificativeInvoice_blankVerifactuType_isANoOp() throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getEtvfacInvType()).thenReturn(null);

    assertNull(runGuard(invoice, false, true, true, true));
  }

  // ---------------------------------------------------------------------------
  // fail-open — a read error must never block, never throw
  // ---------------------------------------------------------------------------

  @Test
  public void failsOpenWhenTheInvoiceCannotBeRead() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(RectificationFiscalCodeGuard.checkAndApply(completeAction(INVOICE_ID)));
    }
  }
}
