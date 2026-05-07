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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for SalesQuotationHeaderHandler.handle().
 *
 * Verifies that both total-discount interception paths are wired correctly:
 * <ul>
 *   <li>applyTotalDiscountBeforeComplete for documentAction=CO (CRUD PATCH path)</li>
 *   <li>syncTotalDiscountOnDocAction for DocAction process-button (SendToEvaluationModal DR→UE path)</li>
 * </ul>
 *
 * The static helpers are tested exhaustively in AbstractOrderHeaderHandlerTest.
 * These tests verify only that the handler calls them with the injected service and isInvoice=false.
 */
public class SalesQuotationHeaderHandlerTest {

  private static final String QUOTATION_ID = "quotation-abc-123";

  // ── helpers ───────────────────────────────────────────────────────────────

  private static SalesQuotationHeaderHandler handlerWith(
      TotalDiscountService svc, NeoCloneRecordHandler clone) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    setField(handler, "cloneRecordHandler", clone);
    return handler;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static JSONObject bodyWith(String key, String value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ── applyTotalDiscountBeforeComplete wiring ───────────────────────────────

  /**
   * CRUD PATCH with documentAction=CO fires recalculate via applyTotalDiscountBeforeComplete.
   */
  @Test
  public void testHandle_crudPatchCO_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc).recalculate(QUOTATION_ID, false);
  }

  /**
   * CRUD PATCH with documentAction=WP does not fire recalculate.
   */
  @Test
  public void testHandle_crudPatchNotCO_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "WP"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── syncTotalDiscountOnDocAction wiring ───────────────────────────────────

  /**
   * ACTION DocAction fires recalculate via syncTotalDiscountOnDocAction
   * (mirrors the SendToEvaluationModal DR→UE path).
   */
  @Test
  public void testHandle_actionDocAction_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("DocAction")
        .recordId(QUOTATION_ID).build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc).recalculate(QUOTATION_ID, false);
  }

  /**
   * ACTION documentAction with empty body → neither path fires recalculate.
   */
  @Test
  public void testHandle_actionDocumentActionNoBody_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(QUOTATION_ID).requestBody(new JSONObject())
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── clone dispatch ────────────────────────────────────────────────────────

  /**
   * handle() short-circuits when the clone handler responds.
   */
  @Test
  public void testHandle_cloneResponds_shortCircuits() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord")
        .recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handlerWith(svc, clone).handle(ctx));
  }

  /**
   * handle() returns null when no downstream handler matches.
   */
  @Test
  public void testHandle_noMatchingHandler_returnsNull() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(null);

    assertNull(handlerWith(svc, clone).handle(ctx));
  }
}
