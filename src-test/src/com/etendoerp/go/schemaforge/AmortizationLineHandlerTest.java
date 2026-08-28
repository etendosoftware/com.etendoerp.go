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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;

/**
 * Unit tests for {@link AmortizationLineHandler}.
 *
 * <p>Covers the DELETE guard: passthrough for non-CRUD/non-DELETE traffic, the confirmed/posted
 * block (409), the pending pass-through (null → default CRUD deletes), and the fail-open/edge-case
 * paths (record not found, orphan line, OBDal failure). All static OBDal calls are mocked with
 * try-with-resources MockedStatic, matching {@code AssetsHandlerTest}'s convention.</p>
 */
public class AmortizationLineHandlerTest {

  private final AmortizationLineHandler handler = new AmortizationLineHandler();

  // ─── helpers ──────────────────────────────────────────────────────────────

  private NeoContext buildContext(String method, String recordId) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(recordId)
        .build();
  }

  private NeoContext buildContext(NeoEndpointType type, String method, String recordId) {
    return NeoContext.builder()
        .endpointType(type)
        .httpMethod(method)
        .recordId(recordId)
        .build();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Passthrough guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleNonCrudEndpointReturnsNull() throws Exception {
    NeoContext ctx = buildContext(NeoEndpointType.SELECTOR, "DELETE", "line-1");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleGetMethodReturnsNull() throws Exception {
    NeoContext ctx = buildContext("GET", "line-1");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandlePatchMethodReturnsNull() throws Exception {
    NeoContext ctx = buildContext("PATCH", "line-1");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleDeleteNullRecordIdReturnsNull() throws Exception {
    NeoContext ctx = buildContext("DELETE", null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleDeleteEmptyRecordIdReturnsNull() throws Exception {
    NeoContext ctx = buildContext("DELETE", "");
    assertNull(handler.handle(ctx));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DELETE — record/parent resolution edge cases
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleDeleteLineNotFoundReturnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      when(dalInstance.get(AmortizationLine.class, "missing")).thenReturn(null);

      NeoContext ctx = buildContext("DELETE", "missing");
      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void testHandleDeleteOrphanLineNoParentReturnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      when(dalInstance.get(AmortizationLine.class, "line-orphan")).thenReturn(line);
      when(line.getAmortization()).thenReturn(null);

      NeoContext ctx = buildContext("DELETE", "line-orphan");
      assertNull(handler.handle(ctx));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DELETE — pending plan (neither processed nor posted) → falls through
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleDeletePendingPlanReturnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      Amortization amortization = mock(Amortization.class);
      when(dalInstance.get(AmortizationLine.class, "line-pending")).thenReturn(line);
      when(line.getAmortization()).thenReturn(amortization);
      when(amortization.getProcessed()).thenReturn("N");
      when(amortization.getPosted()).thenReturn("N");

      NeoContext ctx = buildContext("DELETE", "line-pending");
      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void testHandleDeletePendingPlanNullFlagsReturnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      Amortization amortization = mock(Amortization.class);
      when(dalInstance.get(AmortizationLine.class, "line-null-flags")).thenReturn(line);
      when(line.getAmortization()).thenReturn(amortization);
      when(amortization.getProcessed()).thenReturn(null);
      when(amortization.getPosted()).thenReturn(null);

      NeoContext ctx = buildContext("DELETE", "line-null-flags");
      assertNull(handler.handle(ctx));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DELETE — confirmed/posted plan → 409 with descriptive message
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleDeleteProcessedPlanReturns409() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      Amortization amortization = mock(Amortization.class);
      when(dalInstance.get(AmortizationLine.class, "line-confirmed")).thenReturn(line);
      when(line.getAmortization()).thenReturn(amortization);
      when(amortization.getId()).thenReturn("amort-1");
      when(amortization.getProcessed()).thenReturn("Y");
      when(amortization.getPosted()).thenReturn("N");

      NeoContext ctx = buildContext("DELETE", "line-confirmed");
      NeoResponse response = handler.handle(ctx);

      assertEquals(409, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals(409, error.getInt("status"));
      assertTrue("message should mention the plan is confirmed",
          error.getString("message").toLowerCase().contains("confirmed"));
    }
  }

  @Test
  public void testHandleDeletePostedPlanReturns409() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      Amortization amortization = mock(Amortization.class);
      when(dalInstance.get(AmortizationLine.class, "line-posted")).thenReturn(line);
      when(line.getAmortization()).thenReturn(amortization);
      when(amortization.getId()).thenReturn("amort-2");
      when(amortization.getProcessed()).thenReturn("N");
      when(amortization.getPosted()).thenReturn("Y");

      NeoContext ctx = buildContext("DELETE", "line-posted");
      NeoResponse response = handler.handle(ctx);

      assertEquals(409, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertTrue("message should mention posted", error.getString("message").toLowerCase().contains("posted"));
    }
  }

  @Test
  public void testHandleDeleteProcessedAndPostedPlanReturns409() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      AmortizationLine line = mock(AmortizationLine.class);
      Amortization amortization = mock(Amortization.class);
      when(dalInstance.get(AmortizationLine.class, "line-both")).thenReturn(line);
      when(line.getAmortization()).thenReturn(amortization);
      when(amortization.getId()).thenReturn("amort-3");
      when(amortization.getProcessed()).thenReturn("Y");
      when(amortization.getPosted()).thenReturn("Y");

      NeoContext ctx = buildContext("DELETE", "line-both");
      NeoResponse response = handler.handle(ctx);

      assertEquals(409, response.getHttpStatus());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Exception handling — fail-open
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleDeleteOBDalThrowsFailsOpenReturnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenThrow(new RuntimeException("DB unavailable"));

      NeoContext ctx = buildContext("DELETE", "line-err");
      // Exception is caught — handler fails open, never throws, never false-blocks.
      assertNull(handler.handle(ctx));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // afterHandle — default pass-through (not overridden)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testAfterHandleAlwaysReturnsNull() throws Exception {
    NeoContext ctx = buildContext("DELETE", "line-1");
    assertNull(handler.afterHandle(ctx));
  }
}
