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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.util.NeoRecordVersion;

/**
 * Unit tests for the optimistic-locking guards {@code NeoCrudHandler} gained in ETP-5073 / DOC-04:
 * {@code validateUpdateRequest} (the 400 for an update that carries no {@code updated}),
 * {@code detectStaleRecord} and {@code buildStaleRecordResponse} (the 409 for one that carries a
 * stale value).
 *
 * <p>All three are private, so they are reached by reflection — the same approach
 * {@code NeoCrudHandlerTest} already uses throughout for this class. {@code NeoRecordVersion} is
 * mocked statically rather than being driven through a mocked DAL: what is under test here is the
 * WIRING (does a stale verdict become a 409 with the right discriminator), while the verdict itself
 * is covered by {@code NeoRecordVersionTest}.
 */
class NeoCrudHandlerStaleRecordTest {

  private static final String DAL_ENTITY = "Order";
  private static final String RECORD_ID = "95E2A8B50A254B2AAE6774B8C2F28120";
  private static final String UPDATED_TOKEN = "2026-08-28T12:30:15-03:00";

  private NeoCrudHandler handler;

  @BeforeEach
  void setUp() {
    handler = new NeoCrudHandler(mock(NeoServlet.class));
  }

  private static NeoContext updateContext(String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName("testSpec")
        .entityName("testEntity")
        .httpMethod("PUT")
        .recordId(recordId)
        .requestBody(body)
        .obContext(mock(OBContext.class))
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static JSONObject bodyWithUpdated(Object value) throws Exception {
    JSONObject body = new JSONObject();
    body.put("updated", value);
    return body;
  }

  private NeoResponse invokeValidateUpdateRequest(NeoContext context) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("validateUpdateRequest",
        NeoContext.class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(handler, context);
  }

  private NeoResponse invokeDetectStaleRecord(NeoContext context, String dalEntityName)
      throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("detectStaleRecord", NeoContext.class,
        String.class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(handler, context, dalEntityName);
  }

  private NeoResponse invokeBuildStaleRecordResponse(String message) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("buildStaleRecordResponse",
        String.class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(handler, message);
  }

  /**
   * The case that matters most for not breaking every existing caller: a well-formed update must
   * pass the new guard untouched. If this fails, no record in the product can be saved at all.
   */
  @Test
  @DisplayName("an update carrying `updated` passes validation")
  void updateWithUpdatedIsAccepted() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(
        updateContext(RECORD_ID, bodyWithUpdated(UPDATED_TOKEN)));
    assertNull(response);
  }

  @Test
  @DisplayName("an update with no `updated` key is refused as 400 missing_updated")
  void updateWithoutUpdatedIsRefused() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(updateContext(RECORD_ID, new JSONObject()));

    assertNotNull(response);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, body.getInt("status"));
    assertEquals("missing_updated", body.getString("error"));
    assertEquals("updated", body.getString("field"));
    assertFalse(body.getString("detail").trim().isEmpty());
    assertFalse(body.getString("hint").trim().isEmpty());
  }

  @Test
  @DisplayName("an update with no request body at all is refused as 400 missing_updated")
  void updateWithoutBodyIsRefused() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(updateContext(RECORD_ID, null));
    assertNotNull(response);
    assertEquals("missing_updated", response.getBody().getString("error"));
  }

  /**
   * A JSON {@code null} is not a value. Read back through jettison's {@code optString} it arrives
   * as the four characters {@code "null"}, not as a Java null, so without the literal-string guard
   * it would sail past the blank check and be sent to the parser as if it were a real token.
   */
  @Test
  @DisplayName("an explicit JSON null `updated` is refused as 400 missing_updated")
  void updateWithJsonNullUpdatedIsRefused() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(
        updateContext(RECORD_ID, bodyWithUpdated(JSONObject.NULL)));
    assertNotNull(response);
    assertEquals("missing_updated", response.getBody().getString("error"));
  }

  @Test
  @DisplayName("a blank `updated` is refused as 400 missing_updated")
  void updateWithBlankUpdatedIsRefused() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(
        updateContext(RECORD_ID, bodyWithUpdated("   ")));
    assertNotNull(response);
    assertEquals("missing_updated", response.getBody().getString("error"));
  }

  /**
   * The pre-existing guard must still fire first and keep its own shape: a missing record ID is a
   * different mistake with a different remedy, and reporting it as {@code missing_updated} would
   * send the caller to fetch a value that was never the problem.
   */
  @Test
  @DisplayName("a missing record id is still reported as its own 400, not as missing_updated")
  void missingRecordIdKeepsItsOwnError() throws Exception {
    NeoResponse response = invokeValidateUpdateRequest(updateContext(null, new JSONObject()));

    assertNotNull(response);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    assertNotEquals("missing_updated", response.getBody().optString("error", ""));
  }

  @Test
  @DisplayName("a stale verdict becomes a 409 stale_record response")
  void staleVerdictBecomesConflict() throws Exception {
    NeoContext context = updateContext(RECORD_ID, bodyWithUpdated(UPDATED_TOKEN));
    try (MockedStatic<NeoRecordVersion> version = mockStatic(NeoRecordVersion.class)) {
      version.when(() -> NeoRecordVersion.isStale(anyString(), anyString(), anyString()))
          .thenReturn(true);

      NeoResponse response = invokeDetectStaleRecord(context, DAL_ENTITY);

      assertNotNull(response);
      assertEquals(HttpServletResponse.SC_CONFLICT, response.getHttpStatus());
      assertEquals("stale_record", response.getBody().getString("error"));
    }
  }

  @Test
  @DisplayName("a fresh verdict lets the write proceed")
  void freshVerdictReturnsNull() throws Exception {
    NeoContext context = updateContext(RECORD_ID, bodyWithUpdated(UPDATED_TOKEN));
    try (MockedStatic<NeoRecordVersion> version = mockStatic(NeoRecordVersion.class)) {
      version.when(() -> NeoRecordVersion.isStale(anyString(), anyString(), anyString()))
          .thenReturn(false);

      assertNull(invokeDetectStaleRecord(context, DAL_ENTITY));
    }
  }

  /**
   * {@code error} is the discriminator the React client keys its reload-and-reapply offer off, and
   * a duplicate key answers 409 too — so the code, not the status, is what must be asserted. The
   * human-readable {@code message} is passed through so a body read by a person still says
   * something; {@code detail} and {@code hint} carry the machine-actionable remedy.
   */
  @Test
  @DisplayName("the 409 body carries the discriminator, the message, and the remedy")
  void staleRecordResponseShape() throws Exception {
    NeoResponse response = invokeBuildStaleRecordResponse("Somebody else changed this record.");

    assertEquals(HttpServletResponse.SC_CONFLICT, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(HttpServletResponse.SC_CONFLICT, body.getInt("status"));
    assertEquals("stale_record", body.getString("error"));
    assertNotEquals("conflict", body.getString("error"));
    assertEquals("Somebody else changed this record.", body.getString("message"));
    assertFalse(body.getString("detail").trim().isEmpty());
    assertFalse(body.getString("hint").trim().isEmpty());
  }
}
