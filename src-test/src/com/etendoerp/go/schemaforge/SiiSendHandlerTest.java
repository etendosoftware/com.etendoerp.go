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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.process.CorrectDuplicateInvoiceError;

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

  // ── translateCorrectDuplicateResult() — ETP-5272 ──────────────────────────
  //
  // CorrectDuplicateInvoiceError#doExecute returns
  // {"responseActions":[{"showMsgInView":{"msgType","msgTitle","msgText"}}]} — a shape
  // specific to this classic process, distinct from the generic OBUIAPP
  // showMsgInProcessView translated elsewhere. These tests exercise the dedicated
  // translator directly against synthetic JSON, no DB/AEAT access required.

  /**
   * Builds a {@code CorrectDuplicateInvoiceError#doExecute}-shaped result with a single
   * {@code showMsgInView} action.
   */
  private static JSONObject correctDuplicateResult(String msgType, String msgTitle, String msgText)
      throws JSONException {
    JSONObject showMsgInView = new JSONObject();
    showMsgInView.put("msgType", msgType);
    showMsgInView.put("msgTitle", msgTitle);
    showMsgInView.put("msgText", msgText);
    JSONObject action = new JSONObject();
    action.put("showMsgInView", showMsgInView);
    JSONArray responseActions = new JSONArray();
    responseActions.put(action);
    JSONObject handlerResult = new JSONObject();
    handlerResult.put("responseActions", responseActions);
    return handlerResult;
  }

  @Test
  public void testTranslateCorrectDuplicateResultSuccess() throws JSONException {
    JSONObject handlerResult = correctDuplicateResult("success", "OK", "Invoice sent correctly");

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(200, result.getHttpStatus());
    assertEquals("success", result.getBody().getString("status"));
    assertEquals("Invoice sent correctly", result.getBody().getString("message"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultError() throws JSONException {
    JSONObject handlerResult = correctDuplicateResult("error", "Error", "AEAT rejected the correction");

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(400, result.getHttpStatus());
    assertEquals("error", result.getBody().getString("status"));
    assertEquals("AEAT rejected the correction", result.getBody().getString("message"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultErrorIsCaseInsensitive() throws JSONException {
    JSONObject handlerResult = correctDuplicateResult("Error", "Error", "AEAT rejected the correction");

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(400, result.getHttpStatus());
    assertEquals("Error", result.getBody().getString("status"));
    assertEquals("AEAT rejected the correction", result.getBody().getString("message"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultNullHandlerResultDefaultsToSuccess() {
    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(null);

    assertEquals(200, result.getHttpStatus());
    assertEquals("success", result.getBody().optString("status", null));
    assertFalse(result.getBody().has("message"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultEmptyResponseActionsDefaultsToSuccess() throws JSONException {
    JSONObject handlerResult = new JSONObject().put("responseActions", new JSONArray());

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(200, result.getHttpStatus());
    assertEquals("success", result.getBody().getString("status"));
    assertFalse(result.getBody().has("message"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultMissingResponseActionsKeyDefaultsToSuccess()
      throws JSONException {
    JSONObject handlerResult = new JSONObject().put("someOtherKey", "value");

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(200, result.getHttpStatus());
    assertEquals("success", result.getBody().getString("status"));
  }

  @Test
  public void testTranslateCorrectDuplicateResultMalformedActionDefaultsToSuccess() throws JSONException {
    // responseActions[0] present, but without a showMsgInView key — extractShowMsgInView
    // falls through to null, same as the "no rows"/"missing key" defaults above.
    JSONObject action = new JSONObject().put("someOtherAction", new JSONObject());
    JSONObject handlerResult = new JSONObject().put("responseActions", new JSONArray().put(action));

    NeoResponse result = SiiSendHandler.translateCorrectDuplicateResult(handlerResult);

    assertEquals(200, result.getHttpStatus());
    assertEquals("success", result.getBody().getString("status"));
  }

  // ── executeAction() routing — ETP-5272 ─────────────────────────────────────
  //
  // executeAction must route to CorrectDuplicateInvoiceError (registry-error resend, A1)
  // when Invoice#isAeatsiiErrorRegistral() is true, and to the existing MultiEnvioFactura
  // path (via NeoProcessService#executeObuiappClass, A0) otherwise. CorrectDuplicateInvoiceError
  // is constructed directly (it is not a BaseActionHandler), so its construction is intercepted
  // with Mockito's inline mock-maker (mockConstruction) rather than exercising the real SOAP
  // call inside #doExecute — that call needs a live AEAT/DB environment and is out of scope for
  // a unit test.

  @Test
  public void testExecuteActionRoutesToRegistralCorrectionWhenFlagSet() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.TRUE);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class);
         MockedConstruction<CorrectDuplicateInvoiceError> correctionMock = Mockito.mockConstruction(
             CorrectDuplicateInvoiceError.class,
             (mockInstance, context) -> when(mockInstance.doExecute("inv-registral")).thenReturn(
                 correctDuplicateResult("success", "OK", "Corrected and resent")))) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-registral")).thenReturn(invoice);

      NeoResponse result = handler.executeAction("inv-registral");

      assertEquals(200, result.getHttpStatus());
      assertEquals("success", result.getBody().getString("status"));
      assertEquals("Corrected and resent", result.getBody().getString("message"));

      assertEquals(1, correctionMock.constructed().size());
      verify(correctionMock.constructed().get(0)).doExecute("inv-registral");
      processMock.verifyNoInteractions();
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
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class);
         MockedConstruction<CorrectDuplicateInvoiceError> correctionMock = Mockito.mockConstruction(
             CorrectDuplicateInvoiceError.class)) {
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
      assertTrue("CorrectDuplicateInvoiceError must not be constructed on this path",
          correctionMock.constructed().isEmpty());
      processMock.verify(() -> NeoProcessService.executeObuiappClass(
          eq("org.openbravo.module.sii.process.MultiEnvioFactura"),
          eq("2ECF46DAAEEB486EAF79D3594D50DE5F"),
          any(JSONObject.class)));
    }
  }

  @Test
  public void testExecuteActionRoutesToMultiEnvioFacturaWhenFlagIsNull() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(null);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class);
         MockedConstruction<CorrectDuplicateInvoiceError> correctionMock = Mockito.mockConstruction(
             CorrectDuplicateInvoiceError.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-null-flag")).thenReturn(invoice);
      processMock.when(() -> NeoProcessService.executeObuiappClass(any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-null-flag");

      assertEquals(expected, result);
      assertTrue(correctionMock.constructed().isEmpty());
    }
  }

  @Test
  public void testExecuteActionRoutesToMultiEnvioFacturaWhenInvoiceNotFound() throws Exception {
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("sent", true));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = Mockito.mockStatic(NeoProcessService.class);
         MockedConstruction<CorrectDuplicateInvoiceError> correctionMock = Mockito.mockConstruction(
             CorrectDuplicateInvoiceError.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);
      processMock.when(() -> NeoProcessService.executeObuiappClass(any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = handler.executeAction("inv-missing");

      assertEquals(expected, result);
      assertTrue(correctionMock.constructed().isEmpty());
    }
  }

  @Test
  public void testExecuteActionWrapsRegistralCorrectionExceptionInto500() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isAeatsiiErrorRegistral()).thenReturn(Boolean.TRUE);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedConstruction<CorrectDuplicateInvoiceError> correctionMock = Mockito.mockConstruction(
             CorrectDuplicateInvoiceError.class,
             (mockInstance, context) -> when(mockInstance.doExecute("inv-boom")).thenThrow(
                 new RuntimeException("SOAP timeout")))) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-boom")).thenReturn(invoice);

      NeoResponse result = handler.executeAction("inv-boom");

      assertEquals(500, result.getHttpStatus());
      String message = result.getBody().getString("message");
      assertTrue(message.contains("SII registry-error correction failed"));
      assertTrue(message.contains("SOAP timeout"));
    }
  }
}
