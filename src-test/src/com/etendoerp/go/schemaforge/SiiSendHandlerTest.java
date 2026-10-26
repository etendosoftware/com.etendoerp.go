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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * Unit tests for {@link SiiSendHandler}.
 *
 * <p>Covers action-name matching for the three valid variants and the error
 * message prefix produced when execution fails.
 */
public class SiiSendHandlerTest {

  private final SiiSendHandler handler = new SiiSendHandler();

  @Test
  public void testMatchesCanonicalActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME));
  }

  @Test
  public void testMatchesLegacyActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME_LEGACY));
  }

  @Test
  public void testMatchesQualifierActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME_QUALIFIER));
  }

  @Test
  public void testDoesNotMatchUnrelatedActionName() {
    assertFalse(handler.matchesActionName("registerPayment"));
    assertFalse(handler.matchesActionName("Em_Tbai_Xmlgenerator"));
    assertFalse(handler.matchesActionName(""));
  }

  @Test
  public void testDoesNotMatchNull() {
    assertFalse(handler.matchesActionName(null));
  }

  @Test
  public void testBuildExecutionErrorMessageIncludesPrefix() {
    RuntimeException e = new RuntimeException("network timeout");
    String msg = handler.buildExecutionErrorMessage(e);
    assertTrue(msg.startsWith("SII send failed: "));
    assertTrue(msg.contains("network timeout"));
  }

  @Test
  public void testHandleReturnsNullForGetRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(SiiSendHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(SiiSendHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testNormalizeErrorShapePromotesNestedMultiEnvioFacturaError() throws JSONException {
    // Mirrors NeoProcessService#executeObuiappClass's generic catch: a SOAP/network
    // failure from the classic MultiEnvioFactura process is wrapped via
    // NeoResponse#error(int, String), nesting the text under error.message.
    NeoResponse response = NeoResponse.error(500, "MultiEnvioFactura: SOAP fault from AEAT");

    NeoResponse result = SiiSendHandler.normalizeErrorShape(response);

    assertEquals(response, result);
    assertEquals(500, result.getHttpStatus());
    assertEquals("MultiEnvioFactura: SOAP fault from AEAT", result.getBody().getString("message"));
    assertEquals("MultiEnvioFactura: SOAP fault from AEAT",
        result.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  public void testNormalizeErrorShapeIsIdempotentWhenAlreadyFlat() throws JSONException {
    // As of NeoResponse#ensureTopLevelMessage being applied upstream in
    // NeoProcessService#executeObuiappClass, the body may already carry a
    // top-level message by the time it reaches this handler's delegate.
    JSONObject errorObj = new JSONObject();
    errorObj.put("message", "Original failure");
    JSONObject body = new JSONObject();
    body.put("error", errorObj);
    body.put("message", "Original failure");
    NeoResponse response = new NeoResponse(500, body);

    NeoResponse result = SiiSendHandler.normalizeErrorShape(response);

    assertEquals("Original failure", result.getBody().getString("message"));
  }

  @Test
  public void testNormalizeErrorShapeReturnsNullUnchanged() {
    assertNull(SiiSendHandler.normalizeErrorShape(null));
  }

  // ── remapDriftedFieldLabels — ETP-5745 (5th fix, bilingual full-sentence correction) ────────
  //
  // SIIUtils.invoicePreviousValidations (classic org.openbravo.module.sii) names a field by its
  // CLASSIC label, in Spanish always, in three of its fixed sentences. Two of those labels have
  // drifted from their current Etendo GO label ("Referencia del proveedor" → "Nº documento" —
  // verified live — "Clave tipo factura" → "Tipo factura"); the third ("Descripción SII") has
  // not. Detection still matches the classic SPANISH text (classic never localizes it), but once
  // matched the WHOLE message is now replaced by a GO-authored sentence resolved in the
  // request's language via NeoHints/NeoLanguage, not a substring swap on the classic text — a
  // Spanish-only sentence with just the field name fixed is not a bilingual fix.

  // The language is now an explicit parameter (not read internally via NeoLanguage.currentCode())
  // — see the ETP-5745 "language must be captured BEFORE executeObuiappClass" javadoc for why.
  private static NeoResponse remapWithLanguage(String rawMessage, String languageCode) throws JSONException {
    NeoResponse response = NeoResponse.error(400, rawMessage);
    return SiiSendHandler.remapDriftedFieldLabels(SiiSendHandler.normalizeErrorShape(response), languageCode);
  }

  @Test
  public void purchaseVendorReferenceSentenceBecomesTheFullEsFlagSentence() throws JSONException {
    NeoResponse result = remapWithLanguage(
        "La factura de compra debe contener información en el campo Referencia del proveedor. ",
        "es_ES");

    assertEquals("La factura de compra debe contener información en el campo Nº documento.",
        result.getBody().getString("message"));
  }

  @Test
  public void purchaseVendorReferenceSentenceBecomesTheFullEnglishSentence() throws JSONException {
    NeoResponse result = remapWithLanguage(
        "La factura de compra debe contener información en el campo Referencia del proveedor. ",
        "en_US");

    assertEquals("The purchase invoice must contain information in the Document No. field.",
        result.getBody().getString("message"));
  }

  @Test
  public void invoiceTypeKeySentenceBecomesTheFullEsFlagSentence() throws JSONException {
    NeoResponse result = remapWithLanguage(
        "La factura debe contener información en el campo Clave tipo factura. ", "es_ES");

    assertEquals("La factura debe contener información en el campo Tipo factura.",
        result.getBody().getString("message"));
  }

  @Test
  public void invoiceTypeKeySentenceBecomesTheFullEnglishSentence() throws JSONException {
    NeoResponse result = remapWithLanguage(
        "La factura debe contener información en el campo Clave tipo factura. ", "en_US");

    assertEquals("The invoice must contain information in the Invoice type field.",
        result.getBody().getString("message"));
  }

  /** No resolvable language (no live OBContext, e.g. in these unit tests) falls back to English. */
  @Test
  public void aMatchedSentenceFallsBackToEnglishWithNoResolvableLanguage() throws JSONException {
    NeoResponse result = remapWithLanguage(
        "La factura debe contener información en el campo Clave tipo factura. ", null);

    assertEquals("The invoice must contain information in the Invoice type field.",
        result.getBody().getString("message"));
  }

  @Test
  public void leavesTheSiiDescriptionMessageUntouched_labelDidNotDrift() throws JSONException {
    String original = "La factura debe contener información en el campo Descripción SII. ";

    NeoResponse result = remapWithLanguage(original, "es_ES");

    assertEquals(original, result.getBody().getString("message"));
  }

  @Test
  public void leavesAnUnrecognizedMessageCompletelyUntouched() throws JSONException {
    String original = "MultiEnvioFactura: SOAP fault from AEAT";

    NeoResponse result = remapWithLanguage(original, "es_ES");

    assertEquals(original, result.getBody().getString("message"));
  }

  @Test
  public void remapToleratesANullResponse() {
    assertNull(SiiSendHandler.remapDriftedFieldLabels(null, "en_US"));
  }

  // ── executeAction() routing — ETP-5272 ─────────────────────────────────────
  //
  // executeAction routes to MultiInvoiceSIIModification (registry-error resend, A1)
  // whenever Invoice#isAeatsiiErrorRegistral() is true — regardless of the invoice's
  // AEAT error code (the code check was removed: only two branches exist now). With the
  // registral flag unset (or the invoice not found), the existing MultiEnvioFactura path
  // (A0) is untouched.

  @Test
  public void testExecuteActionRoutesToModificationWhenRegistralFlagTrueWithCode3000() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.TRUE);
    when(invoice.getAeatsiiErrorCode()).thenReturn("3000");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getOrganization()).thenReturn(org);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-registral-3000")).thenReturn(invoice);
      processMock.when(() -> NeoProcessService.executeObuiappClass(
              eq("org.openbravo.module.sii.process.MultiInvoiceSIIModification"),
              eq("F5CCFE8DCAC04FBD9B4A217C6383032B"),
              any(JSONObject.class)))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-registral-3000");

      assertEquals(expected, result);
      processMock.verify(() -> NeoProcessService.executeObuiappClass(
          eq("org.openbravo.module.sii.process.MultiInvoiceSIIModification"),
          eq("F5CCFE8DCAC04FBD9B4A217C6383032B"),
          any(JSONObject.class)));
    }
  }

  @Test
  public void testExecuteActionRoutesToModificationWhenRegistralFlagTrueWithOtherOrNullCode()
      throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.TRUE);
    when(invoice.getAeatsiiErrorCode()).thenReturn(null);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getOrganization()).thenReturn(org);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-registral-null-code")).thenReturn(invoice);
      processMock.when(() -> NeoProcessService.executeObuiappClass(any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-registral-null-code");

      assertEquals(expected, result);
      processMock.verify(() -> NeoProcessService.executeObuiappClass(
          eq("org.openbravo.module.sii.process.MultiInvoiceSIIModification"),
          eq("F5CCFE8DCAC04FBD9B4A217C6383032B"),
          any(JSONObject.class)));
    }
  }

  @Test
  public void testExecuteActionRoutesToMultiEnvioFacturaWhenFlagNotSet() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.FALSE);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getOrganization()).thenReturn(org);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-normal")).thenReturn(invoice);
      processMock.when(() -> NeoProcessService.executeObuiappClass(
              eq("org.openbravo.module.sii.process.MultiEnvioFactura"),
              eq("2ECF46DAAEEB486EAF79D3594D50DE5F"),
              any(JSONObject.class)))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-normal");

      assertEquals(expected, result);
      processMock.verify(() -> NeoProcessService.executeObuiappClass(
          eq("org.openbravo.module.sii.process.MultiEnvioFactura"),
          eq("2ECF46DAAEEB486EAF79D3594D50DE5F"),
          any(JSONObject.class)));
    }
  }

  /**
   * ETP-5745 regression repro (live-verified bug): {@code executeAction} must capture
   * {@code NeoLanguage.currentCode()} BEFORE calling {@code executeObuiappClass}, not after.
   * Simulates the real-world failure directly — the classic bridge call itself (mocked here)
   * changes what {@code NeoLanguage.currentCode()} returns, exactly as the live classic
   * {@code MultiEnvioFactura} call was observed to do (exact clobbering line not pinpointed, see
   * the class javadoc) — and asserts the final replacement message still uses the language
   * captured BEFORE that call, not whatever it resolves to afterwards.
   */
  @Test
  public void executeActionUsesTheLanguageCapturedBeforeTheClassicCallNotAfter() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.FALSE);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getOrganization()).thenReturn(org);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class);
         MockedStatic<NeoLanguage> langMock = Mockito.mockStatic(NeoLanguage.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-lang-clobber")).thenReturn(invoice);

      // Before the call: en_US, as EnvironmentRequestAuthenticator set it from Accept-Language.
      langMock.when(NeoLanguage::currentCode).thenReturn("en_US");
      // The classic bridge call itself flips it to es_ES as a side effect — the observed bug.
      processMock.when(() -> NeoProcessService.executeObuiappClass(anyString(), anyString(), any(JSONObject.class)))
          .thenAnswer(invocation -> {
            langMock.when(NeoLanguage::currentCode).thenReturn("es_ES");
            return NeoResponse.error(400,
                "La factura de compra debe contener información en el campo Referencia del proveedor. ");
          });

      NeoResponse result = handler.executeAction("inv-lang-clobber");

      // Must be the ENGLISH replacement (the pre-call language), never the Spanish one.
      assertEquals("The purchase invoice must contain information in the Document No. field.",
          result.getBody().getString("message"));
    }
  }

  @Test
  public void testExecuteActionRoutesToMultiEnvioFacturaWhenFlagIsNull() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(null);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-null-flag")).thenReturn(invoice);
      processMock.when(() -> NeoProcessService.executeObuiappClass(any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-null-flag");

      assertEquals(expected, result);
    }
  }

  @Test
  public void testExecuteActionRoutesToMultiEnvioFacturaWhenInvoiceNotFound() throws Exception {
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);
      processMock.when(() -> NeoProcessService.executeObuiappClass(any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-missing");

      assertEquals(expected, result);
    }
  }
}
