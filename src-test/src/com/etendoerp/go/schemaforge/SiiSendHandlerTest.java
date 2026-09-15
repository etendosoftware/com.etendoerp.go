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
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

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
