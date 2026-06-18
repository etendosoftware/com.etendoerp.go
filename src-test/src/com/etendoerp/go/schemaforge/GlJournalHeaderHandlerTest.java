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

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

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
}
