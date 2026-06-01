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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link AbstractOrderHeaderHandler#validateExchangeRateBeforeComplete(NeoContext)}.
 *
 * <p>The method is package-private, so it is invoked directly. Covers the no-op paths (non-complete
 * action, blank record id) and the blocking path where the validator returns a message.
 */
public class AbstractOrderHeaderHandlerExchangeRateTest {

  /** ACTION /documentAction with body {@code { fieldValues: { documentAction: "CO" } }}. */
  private static NeoContext completeActionContext(String recordId) throws Exception {
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "CO");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  @Test
  public void testNonCompleteActionReturnsNull() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId("INV1")
        .build();
    assertNull(AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context));
  }

  @Test
  public void testCompleteActionWithBlankRecordIdReturnsNull() throws Exception {
    NeoContext context = completeActionContext("");
    assertNull(AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context));
  }

  @Test
  public void testCompleteActionNoErrorReturnsNull() throws Exception {
    NeoContext context = completeActionContext("INV1");
    Invoice invoice = mock(Invoice.class);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<InvoiceExchangeRateValidator> validator =
            Mockito.mockStatic(InvoiceExchangeRateValidator.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV1")).thenReturn(invoice);
      validator.when(() -> InvoiceExchangeRateValidator.checkRateForCompletion(invoice))
          .thenReturn(null);

      assertNull(AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context));
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }

  @Test
  public void testCompleteActionWithErrorReturnsBadRequest() throws Exception {
    NeoContext context = completeActionContext("INV1");
    Invoice invoice = mock(Invoice.class);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<InvoiceExchangeRateValidator> validator =
            Mockito.mockStatic(InvoiceExchangeRateValidator.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV1")).thenReturn(invoice);
      validator.when(() -> InvoiceExchangeRateValidator.checkRateForCompletion(invoice))
          .thenReturn("No rate USD → EUR");

      NeoResponse response = AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertEquals("No rate USD → EUR",
          response.getBody().getJSONObject("error").getString("message"));
    }
  }

  @Test
  public void testValidatorExceptionIsSwallowedReturnsNull() throws Exception {
    NeoContext context = completeActionContext("INV1");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Invoice.class), any())).thenThrow(new RuntimeException("db down"));

      assertNull(AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context));
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }
}
