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
import static org.junit.Assert.assertSame;
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
 * BLOCKS completion instead of being silently corrected. ETP-6179 extracted the rule into
 * {@link RectificationFiscalCodeGuard#evaluate}, which the one-step invoice creations call
 * directly; {@code checkAndApply} delegates to it with no change in behaviour.
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

  /**
   * Closes the {@code joinWithConjunction} edge case the pair-only tests above don't reach: with
   * SII, TicketBAI AND VERI*FACTU all active and all missing their own code/motive at once, the
   * rejection must name all three, in the fixed SII/TicketBAI/Verifactu order — never just the
   * first two, and never a different order.
   */
  @Test
  public void rectificativeInvoice_allThreeSystemsActiveAndMissing_rejectsNamingAllThreeInOrder()
      throws Exception {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn(null);
    when(invoice.getTbaiReverseinvoicecode()).thenReturn(null);
    when(invoice.getEtvfacInvType()).thenReturn("F1");

    NeoResponse response = runGuard(invoice, true, true, true, true);

    assertNotNull(response);
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    JSONArray systems = body.getJSONObject("messageParams").getJSONArray("systems");
    assertEquals(3, systems.length());
    assertEquals("SII", systems.getString(0));
    assertEquals("TicketBAI", systems.getString(1));
    assertEquals("Verifactu", systems.getString(2));
    assertTrue(body.getString("message").contains("SII"));
    assertTrue(body.getString("message").contains("TicketBAI"));
    assertTrue(body.getString("message").contains("Verifactu"));
    // Never corrects anything on a rejection.
    verify(invoice, never()).setAeatsiiClaveTipo(anyString());
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

  /**
   * A rectificative invoice whose organization cannot be resolved (a bad/missing FK, or the
   * organization record itself returning a {@code null} id) must never block completion — the
   * same fail-open intent as a read error, documented explicitly here: {@code missingSystems}
   * only evaluates a system's configuration when {@code orgId} is non-blank, so a {@code null}
   * orgId yields an empty missing list regardless of how incomplete the invoice's fiscal fields
   * are, and the SII structural correction is skipped the same way.
   */
  @Test
  public void rectificativeInvoice_orgIdNull_neverBlocksCompletion() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isSalesTransaction()).thenReturn(true);
    when(invoice.getTransactionDocument()).thenReturn(mock(DocumentType.class));
    when(invoice.getOrganization()).thenReturn(null);
    when(invoice.getAeatsiiMotivoRectif()).thenReturn(null);
    when(invoice.getTbaiReverseinvoicecode()).thenReturn(null);
    when(invoice.getEtvfacInvType()).thenReturn(null);

    assertNull(runGuard(invoice, true, true, true, true));

    verify(invoice, never()).setAeatsiiClaveTipo(anyString());
    verify(invoice, never()).setAeatsiiTipoRectif(anyString());
  }

  @Test
  public void failsOpenWhenTheInvoiceCannotBeRead() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(RectificationFiscalCodeGuard.checkAndApply(completeAction(INVOICE_ID)));
    }
  }

  // ---------------------------------------------------------------------------
  // ETP-6179 — checkAndApply delegates to evaluate(Invoice), unchanged
  // ---------------------------------------------------------------------------

  /** checkAndApply loads the invoice of the completion and answers exactly what evaluate says. */
  @Test
  public void checkAndApplyDelegatesToEvaluateAndAnswersItsResult() throws Exception {
    Invoice invoice = baseInvoice();
    NeoResponse refusal = NeoResponse.error(422, "sentinel");
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         // evaluate answers the sentinel; every other method (checkAndApply) runs for real
         MockedStatic<RectificationFiscalCodeGuard> guardMock = Mockito.mockStatic(
             RectificationFiscalCodeGuard.class,
             invocation -> "evaluate".equals(invocation.getMethod().getName()) ? refusal
                 : invocation.callRealMethod())) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);

      assertSame(refusal, RectificationFiscalCodeGuard.checkAndApply(completeAction(INVOICE_ID)));

      guardMock.verify(() -> RectificationFiscalCodeGuard.evaluate(invoice));
    }
  }

  /** A request that is not a completion never reaches evaluate. */
  @Test
  public void checkAndApplyNeverEvaluatesANonCompletion() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).httpMethod("PATCH").recordId(INVOICE_ID)
        .requestBody(new JSONObject().put("documentAction", "RE")).build();
    try (MockedStatic<RectificationFiscalCodeGuard> guardMock = Mockito.mockStatic(
        RectificationFiscalCodeGuard.class, Mockito.CALLS_REAL_METHODS)) {
      assertNull(RectificationFiscalCodeGuard.checkAndApply(context));

      guardMock.verify(() -> RectificationFiscalCodeGuard.evaluate(any()), never());
    }
  }

  /** An evaluation that throws still fails OPEN through checkAndApply, as before the split. */
  @Test
  public void checkAndApplyFailsOpenWhenEvaluateThrows() throws Exception {
    Invoice invoice = baseInvoice();
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<RectificativeSupport> rectifMock =
             Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      rectifMock.when(() -> RectificativeSupport.isRectificative(any())).thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
          .thenThrow(new IllegalStateException("config read failed"));

      assertNull(RectificationFiscalCodeGuard.checkAndApply(completeAction(INVOICE_ID)));
    }
  }

  /**
   * The extraction changed nothing: on every scenario of this class, evaluate(invoice) answers
   * what checkAndApply answers for the same invoice — same status, same body, same corrections.
   */
  @Test
  public void evaluateAnswersWhatCheckAndApplyAnswersOnEveryScenario() throws Exception {
    // {rectificative, sii, tbai, verifactu, motive, tbaiCode, vfType}
    Object[][] scenarios = {
        { true, true, true, true, null, null, "F1" },
        { true, true, true, false, null, null, null },
        { true, true, true, true, "R1", "R4", "F1" },
        { true, true, false, false, "R1", null, null },
        { true, false, false, false, null, null, "F1" },
        { false, true, true, true, null, null, "R1" },
        { false, true, true, true, null, null, "F1" },
    };
    for (Object[] c : scenarios) {
      String label = java.util.Arrays.toString(c);
      Invoice viaContext = scenarioInvoice(c);
      Invoice direct = scenarioInvoice(c);
      boolean rectificative = (Boolean) c[0];
      boolean sii = (Boolean) c[1];
      boolean tbai = (Boolean) c[2];
      boolean vf = (Boolean) c[3];

      NeoResponse expected = runGuard(viaContext, rectificative, sii, tbai, vf);
      NeoResponse actual = runEvaluate(direct, rectificative, sii, tbai, vf);

      if (expected == null) {
        assertNull(label, actual);
      } else {
        assertNotNull(label, actual);
        assertEquals(label, expected.getHttpStatus(), actual.getHttpStatus());
        assertEquals(label, expected.getBody().toString(), actual.getBody().toString());
      }
      verify(direct, Mockito.times(Mockito.mockingDetails(viaContext).getInvocations().stream()
          .filter(i -> "setAeatsiiClaveTipo".equals(i.getMethod().getName())).toArray().length))
          .setAeatsiiClaveTipo(anyString());
    }
  }

  /** A null invoice and a purchase invoice are a no-op for evaluate. */
  @Test
  public void evaluateIgnoresANullOrPurchaseInvoice() throws Exception {
    assertNull(RectificationFiscalCodeGuard.evaluate(null));
    Invoice purchase = baseInvoice();
    when(purchase.isSalesTransaction()).thenReturn(false);
    assertNull(RectificationFiscalCodeGuard.evaluate(purchase));
    verify(purchase, never()).getTransactionDocument();
  }

  private Invoice scenarioInvoice(Object[] c) {
    Invoice invoice = baseInvoice();
    when(invoice.getAeatsiiMotivoRectif()).thenReturn((String) c[4]);
    when(invoice.getTbaiReverseinvoicecode()).thenReturn((String) c[5]);
    when(invoice.getEtvfacInvType()).thenReturn((String) c[6]);
    when(invoice.getAeatsiiClaveTipo()).thenReturn("F1");
    return invoice;
  }

  private NeoResponse runEvaluate(Invoice invoice, boolean isRectificative, boolean siiActive,
      boolean tbaiActive, boolean verifactuActive) throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<RectificativeSupport> rectifMock = Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      rectifMock.when(() -> RectificativeSupport.isRectificative(any()))
          .thenReturn(isRectificative);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
          .thenReturn(siiActive);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
          .thenReturn(tbaiActive);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.configExists(
              SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL, ORG_ID))
          .thenReturn(verifactuActive);

      return RectificationFiscalCodeGuard.evaluate(invoice);
    }
  }
}
