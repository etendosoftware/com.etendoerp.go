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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the static helpers in AbstractOrderHeaderHandler:
 * {@code applyTotalDiscountBeforeComplete} and {@code syncTotalDiscountOnDocAction}.
 *
 * Tests verify which combinations of endpoint type, HTTP method, field name, and
 * request body cause TotalDiscountService.recalculate() to fire — and which do not.
 */
public class AbstractOrderHeaderHandlerTest {

  private static final String ORDER_ID = "order-abc-123";

  // ── applyTotalDiscountBeforeComplete ─────────────────────────────────────

  /**
   * Service null → no recalculate call.
   */
  @Test
  public void testApplyTotalDiscount_nullService_doesNotThrow() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, null, false);
  }

  /**
   * Record id null → no recalculate call.
   */
  @Test
  public void testApplyTotalDiscount_nullRecordId_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(null)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * Empty record id → no recalculate call.
   */
  @Test
  public void testApplyTotalDiscount_emptyRecordId_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId("")
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * CRUD PATCH with documentAction=CO → recalculate called for order.
   */
  @Test
  public void testApplyTotalDiscount_crudPatchCO_callsRecalculate() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc).recalculate(ORDER_ID, false);
  }

  /**
   * CRUD PUT with documentAction=CO → recalculate called.
   */
  @Test
  public void testApplyTotalDiscount_crudPutCO_callsRecalculate() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc).recalculate(ORDER_ID, false);
  }

  /**
   * CRUD PATCH with documentAction=WP → no recalculate call.
   */
  @Test
  public void testApplyTotalDiscount_crudPatchNotCO_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .requestBody(bodyWith("documentAction", "WP"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * CRUD GET → no recalculate call regardless of body.
   */
  @Test
  public void testApplyTotalDiscount_crudGet_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION POST to documentAction field with { docAction: "CO" } → recalculate called.
   */
  @Test
  public void testApplyTotalDiscount_actionDocActionFieldDocActionCO_callsRecalculate() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(ORDER_ID).requestBody(bodyWith("docAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc).recalculate(ORDER_ID, false);
  }

  /**
   * ACTION POST to documentAction field with { fieldValues: { documentAction: "CO" } } → recalculate called.
   */
  @Test
  public void testApplyTotalDiscount_actionFieldValuesDocumentActionCO_callsRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(ORDER_ID).requestBody(new JSONObject().put("fieldValues", fieldValues))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc).recalculate(ORDER_ID, false);
  }

  /**
   * ACTION POST to documentAction field with { fieldValues: { documentAction: "WP" } } → no recalculate.
   */
  @Test
  public void testApplyTotalDiscount_actionFieldValuesNotCO_skips() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    JSONObject fieldValues = new JSONObject().put("documentAction", "WP");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(ORDER_ID).requestBody(new JSONObject().put("fieldValues", fieldValues))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION POST to DocAction field (not documentAction) → no recalculate from applyTotalDiscountBeforeComplete.
   */
  @Test
  public void testApplyTotalDiscount_actionDifferentField_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("DocAction")
        .recordId(ORDER_ID).requestBody(bodyWith("docAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * isInvoice=true flag passes through to recalculate correctly.
   */
  @Test
  public void testApplyTotalDiscount_invoiceFlag_passesIsInvoiceTrue() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(ctx, svc, true);

    verify(svc).recalculate(ORDER_ID, true);
  }

  // ── syncTotalDiscountOnDocAction ──────────────────────────────────────────

  /**
   * Service null → no throw.
   */
  @Test
  public void testSyncDocAction_nullService_doesNotThrow() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("DocAction").recordId(ORDER_ID).build();
    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, null, false);
  }

  /**
   * Not ACTION endpoint → no recalculate.
   */
  @Test
  public void testSyncDocAction_notActionEndpoint_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).fieldName("DocAction").recordId(ORDER_ID).build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION but fieldName != DocAction → no recalculate.
   */
  @Test
  public void testSyncDocAction_actionButWrongFieldName_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("documentAction").recordId(ORDER_ID).build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION + DocAction field + null recordId → no recalculate.
   */
  @Test
  public void testSyncDocAction_nullRecordId_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("DocAction").recordId(null).build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION + DocAction field + empty recordId → no recalculate.
   */
  @Test
  public void testSyncDocAction_emptyRecordId_skips() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("DocAction").recordId("").build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, false);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  /**
   * ACTION + DocAction field + valid recordId → recalculate called (mirrors SendToEvaluationModal path).
   */
  @Test
  public void testSyncDocAction_validContext_callsRecalculate() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("DocAction").recordId(ORDER_ID).build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, false);

    verify(svc).recalculate(ORDER_ID, false);
  }

  /**
   * isInvoice=true passes through correctly on the DocAction path.
   */
  @Test
  public void testSyncDocAction_invoiceFlag_passesIsInvoiceTrue() {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("DocAction").recordId(ORDER_ID).build();

    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(ctx, svc, true);

    verify(svc).recalculate(ORDER_ID, true);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JSONObject bodyWith(String key, String value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
