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
import static org.mockito.Mockito.mockStatic;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;

/**
 * Unit tests for {@link GlJournalHeaderHandler}.
 *
 * <p>Covers the testable pure-logic paths that do not require a running Etendo DB:
 * <ul>
 *   <li>{@code afterHandle()} — always returns null.</li>
 *   <li>{@code handle()} pass-through for non-ACTION / non-POST endpoints.</li>
 *   <li>Complete-action detection: wrong endpoint type, wrong fieldName, wrong docAction value.</li>
 *   <li>Early-exit in {@code completeJournal()} when the record id is absent (400).</li>
 * </ul>
 */
public class GlJournalHeaderHandlerTest {

  private final GlJournalHeaderHandler handler = new GlJournalHeaderHandler();

  // ─── afterHandle() ───────────────────────────────────────────────────────────

  @Test
  public void afterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(ctx));
  }

  // ─── handle(): non-ACTION, non-POST endpoints ─────────────────────────────

  @Test
  public void handleIgnoresCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresDefaultsEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("GET")
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── handle(): isCompleteAction paths ────────────────────────────────────

  @Test
  public void handleIgnoresActionWithWrongFieldName() {
    // ACTION endpoint but field is not "documentAction" → not a complete-action
    // Falls through to the POST guard → not POST → null.
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherField")
        .httpMethod("POST")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresActionWithNullBody() {
    // ACTION + correct fieldName but body is null → isCompleteAction returns false.
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(null)
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresActionWhenDocActionIsNotCO() throws Exception {
    // ACTION + documentAction field, body says "PO" (post) not "CO" → not a complete-action.
    JSONObject body = new JSONObject();
    body.put("documentAction", "PO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── completeJournal(): missing record id → 400 ──────────────────────────

  @Test
  public void handleCompleteActionWithNullRecordIdReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId(null)
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleCompleteActionWithEmptyRecordIdReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleCompleteActionFromFieldValuesNested() throws Exception {
    // Draft-mode sends documentAction under fieldValues, not at body root.
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "CO");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    // Detected as a complete action; no recordId → 400
    assertEquals(400, response.getHttpStatus());
  }

  // ─── handle(): POST short-circuits before DB ──────────────────────────────

  @Test
  public void handlePostSkipsInjectionWhenMultiGlIsY() throws Exception {
    // Multi-ledger journals do not require a C_AcctSchema_ID — handler must exit early.
    JSONObject body = new JSONObject();
    body.put("multigeneralLedger", "Y");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handlePostSkipsInjectionWhenAccountingSchemaAlreadyPresent() throws Exception {
    // Explicit caller-provided value must not be overwritten by the handler.
    JSONObject body = new JSONObject();
    body.put("accountingSchema", "SOME_ACCT_SCHEMA_ID");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── isCompleteAction(): fieldValues present with non-CO docAction ─────────

  @Test
  public void handleActionWithFieldValuesDocActionNotCO() throws Exception {
    // isCompleteAction() ternary true-branch: fieldValues present but docAction ≠ CO → false.
    // httpMethod=GET so handle() short-circuits before any DB call.
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "VD");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("GET")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── completeJournal(): exception path → 500 ─────────────────────────────

  @Test
  public void handleCompleteActionExceptionReturns500() throws Exception {
    // Complete action with non-empty recordId; buildVariablesSecureApp throws →
    // completeJournal() catch block returns 500.
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("GL-JOURNAL-001")
        .build();
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("session unavailable"));
      NeoResponse response = handler.handle(ctx);
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── handle(): POST injection — session has no schema → warn path ─────────

  @Test
  public void handlePostInjectionAcctSchemaEmpty() throws Exception {
    // buildVariablesSecureApp returns a VSA with no $C_AcctSchema_ID in session →
    // handler logs warn and returns null without modifying the body.
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(vars);
      assertNull(handler.handle(ctx));
    }
  }

  // ─── handle(): POST injection — session has schema → inject into body ─────

  @Test
  public void handlePostInjectsAcctSchemaId() throws Exception {
    // buildVariablesSecureApp returns a VSA with $C_AcctSchema_ID set → handler
    // injects the value into the request body and returns null.
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    vars.setSessionValue("$C_AcctSchema_ID", "ACCT-SCHEMA-001");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(vars);
      assertNull(handler.handle(ctx));
      assertEquals("ACCT-SCHEMA-001", body.getString("accountingSchema"));
    }
  }
}
