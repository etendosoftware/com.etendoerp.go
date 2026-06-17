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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

/**
 * Unit tests for {@link AssetsHandler}.
 *
 * <p>Covers handle() passthrough guards, POST computation, PATCH computation,
 * OBDal fallback paths, parseUsableLifeMonths edge cases, and exception handling.
 * All static OBDal calls are mocked with try-with-resources MockedStatic.</p>
 */
public class AssetsHandlerTest {

  private final AssetsHandler handler = new AssetsHandler();

  // ─── helpers ──────────────────────────────────────────────────────────────

  private NeoContext buildContext(String method, JSONObject body, String recordId) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .requestBody(body)
        .recordId(recordId)
        .build();
  }

  /** Jan 15 2026 as java.util.Date (year offset from 1900). */
  @SuppressWarnings("deprecation")
  private Date date20260115() {
    return new Date(2026 - 1900, 0, 15); // Jan 15 2026
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Passthrough guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandleNonCrudEndpointReturnsNull() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("POST")
        .requestBody(new JSONObject())
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleGetMethodReturnsNull() throws Exception {
    NeoContext ctx = buildContext("GET", new JSONObject(), null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleDeleteMethodReturnsNull() throws Exception {
    NeoContext ctx = buildContext("DELETE", new JSONObject(), null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleNullBodyReturnsNull() throws Exception {
    NeoContext ctx = buildContext("POST", null, null);
    assertNull(handler.handle(ctx));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // POST — missing field guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePostBothFieldsAbsentReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertFalse("endDate should not be injected", body.has("depreciationEndDate"));
  }

  @Test
  public void testHandlePostOnlyStartDateReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertFalse("endDate should not be injected without usableLifeMonths", body.has("depreciationEndDate"));
  }

  @Test
  public void testHandlePostOnlyUsableLifeMonthsReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);
    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertFalse("endDate should not be injected without startDate", body.has("depreciationEndDate"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // POST — happy path computation
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePostBothFieldsPresentComputesEndDate() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", 12);

    NeoContext ctx = buildContext("POST", body, null);
    NeoResponse result = handler.handle(ctx);

    // handle() always returns null (continues to default CRUD)
    assertNull(result);
    assertTrue("depreciationEndDate should be injected", body.has("depreciationEndDate"));
    assertEquals("2027-01-15", body.getString("depreciationEndDate"));
  }

  @Test
  public void testHandlePostZeroMonthsEndDateEqualsStartDate() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-06-01");
    body.put("usableLifeMonths", 0);

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertEquals("2026-06-01", body.getString("depreciationEndDate"));
  }

  @Test
  public void testHandlePostLargeMonthsComputed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2020-03-01");
    body.put("usableLifeMonths", 60); // 5 years

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertEquals("2025-03-01", body.getString("depreciationEndDate"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PATCH — skip when neither source field touched
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePatchNeitherFieldInBodyReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("description", "some other field");
    NeoContext ctx = buildContext("PATCH", body, "record-123");
    assertNull(handler.handle(ctx));
    assertFalse(body.has("depreciationEndDate"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PATCH — both fields in body (no OBDal needed)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePatchBothFieldsInBodyComputesEndDate() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", 12);

    NeoContext ctx = buildContext("PATCH", body, "record-123");
    assertNull(handler.handle(ctx));
    assertEquals("2027-01-15", body.getString("depreciationEndDate"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PATCH — only usableLifeMonths in body, loads startDate from record
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePatchOnlyUsableLifeMonthsLoadsStartDateFromRecord() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      Asset asset = mock(Asset.class);
      when(dalInstance.get(Asset.class, "record-123")).thenReturn(asset);
      when(asset.getDepreciationStartDate()).thenReturn(date20260115());

      NeoContext ctx = buildContext("PATCH", body, "record-123");
      assertNull(handler.handle(ctx));
      assertEquals("2027-01-15", body.getString("depreciationEndDate"));
    }
  }

  @Test
  public void testHandlePatchOnlyUsableLifeMonthsRecordNotFoundSkips() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      when(dalInstance.get(Asset.class, "record-xyz")).thenReturn(null);

      NeoContext ctx = buildContext("PATCH", body, "record-xyz");
      assertNull(handler.handle(ctx));
      assertFalse("endDate should not be injected when record not found", body.has("depreciationEndDate"));
    }
  }

  @Test
  public void testHandlePatchOnlyUsableLifeMonthsPersistedStartDateNullSkips() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      Asset asset = mock(Asset.class);
      when(dalInstance.get(Asset.class, "record-123")).thenReturn(asset);
      when(asset.getDepreciationStartDate()).thenReturn(null);

      NeoContext ctx = buildContext("PATCH", body, "record-123");
      assertNull(handler.handle(ctx));
      assertFalse("endDate should not be injected when persisted startDate is null",
          body.has("depreciationEndDate"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PATCH — only depreciationStartDate in body, loads usableLifeMonths from record
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePatchOnlyStartDateLoadsUsableLifeMonthsFromRecord() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      Asset asset = mock(Asset.class);
      when(dalInstance.get(Asset.class, "record-123")).thenReturn(asset);
      when(asset.getUsableLifeMonths()).thenReturn(12L);

      NeoContext ctx = buildContext("PATCH", body, "record-123");
      assertNull(handler.handle(ctx));
      assertEquals("2027-01-15", body.getString("depreciationEndDate"));
    }
  }

  @Test
  public void testHandlePatchOnlyStartDatePersistedUsableLifeMonthsNullSkips() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      Asset asset = mock(Asset.class);
      when(dalInstance.get(Asset.class, "record-123")).thenReturn(asset);
      when(asset.getUsableLifeMonths()).thenReturn(null);

      NeoContext ctx = buildContext("PATCH", body, "record-123");
      assertNull(handler.handle(ctx));
      assertFalse("endDate should not be injected when persisted usableLifeMonths is null",
          body.has("depreciationEndDate"));
    }
  }

  @Test
  public void testHandlePatchOnlyStartDateRecordNotFoundSkips() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);
      when(dalInstance.get(Asset.class, "record-abc")).thenReturn(null);

      NeoContext ctx = buildContext("PATCH", body, "record-abc");
      assertNull(handler.handle(ctx));
      assertFalse(body.has("depreciationEndDate"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // parseUsableLifeMonths edge cases (exercised via PATCH)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePostUsableLifeMonthsAsIntegerParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", 24);

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertEquals("2028-01-15", body.getString("depreciationEndDate"));
  }

  @Test
  public void testHandlePostUsableLifeMonthsAsStringIntegerParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", "24");

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertEquals("2028-01-15", body.getString("depreciationEndDate"));
  }

  @Test
  public void testHandlePostUsableLifeMonthsAsDecimalStringParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", "24.0");

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertEquals("2028-01-15", body.getString("depreciationEndDate"));
  }

  @Test
  public void testHandlePostUsableLifeMonthsUnparseableStringSkips() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "2026-01-15");
    body.put("usableLifeMonths", "abc");

    NeoContext ctx = buildContext("POST", body, null);
    assertNull(handler.handle(ctx));
    assertFalse("endDate should not be injected when usableLifeMonths cannot be parsed",
        body.has("depreciationEndDate"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // loadStartDateFromRecord — null recordId guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePatchNullRecordIdSkipsOBDal() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal dalInstance = mock(OBDal.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(dalInstance);

      NeoContext ctx = buildContext("PATCH", body, null);
      assertNull(handler.handle(ctx));
      assertFalse("endDate should not be injected when recordId is null",
          body.has("depreciationEndDate"));
      // OBDal.get should never be called when recordId is null
      verify(dalInstance, never()).get(Asset.class, null);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Exception handling
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testHandlePostInvalidStartDateFormatDoesNotCrash() throws Exception {
    JSONObject body = new JSONObject();
    body.put("depreciationStartDate", "not-a-date");
    body.put("usableLifeMonths", 12);

    NeoContext ctx = buildContext("POST", body, null);
    // DateTimeParseException should be caught — no throw, returns null
    assertNull(handler.handle(ctx));
    assertFalse("endDate should not be injected on parse failure", body.has("depreciationEndDate"));
  }

  @Test
  public void testHandlePatchOBDalThrowsDoesNotCrash() throws Exception {
    JSONObject body = new JSONObject();
    body.put("usableLifeMonths", 12);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenThrow(new RuntimeException("DB unavailable"));

      NeoContext ctx = buildContext("PATCH", body, "record-123");
      // Exception is caught inside loadStartDateFromRecord — should not propagate
      assertNull(handler.handle(ctx));
      assertFalse(body.has("depreciationEndDate"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // afterHandle — always returns null (NeoHandler default pass-through)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testAfterHandleAlwaysReturnsNull() throws Exception {
    NeoContext ctx = buildContext("POST", new JSONObject(), null);
    // AssetsHandler does not override afterHandle — the default from NeoHandler
    // must return null. Verify the interface contract is upheld.
    assertNull(handler.afterHandle(ctx));
  }
}
