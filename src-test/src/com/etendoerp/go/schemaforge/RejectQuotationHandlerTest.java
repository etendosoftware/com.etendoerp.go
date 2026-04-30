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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.RejectReason;

/**
 * Unit tests for {@link RejectQuotationHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Dispatch fall-through (non-ACTION endpoint, wrong fieldName, non-POST method).</li>
 *   <li>Validation (missing recordId, missing reason, quotation not found, wrong status, reason inactive).</li>
 *   <li>Happy path (sets DocStatus=CJ, RejectReason, Processed; returns 200 echo).</li>
 *   <li>Defensive body parsing (accepts {@code C_Reject_Reason_ID} as fallback key).</li>
 * </ul>
 *
 * <p>Tests follow the same {@code MockedStatic<OBDal> + MockedStatic<OBContext>} pattern
 * used by {@link CreateDraftInvoiceHandlerTest} so handlers can be exercised without
 * a live DB.
 */
public class RejectQuotationHandlerTest {

  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_REJECT = "rejectQuotation";

  // ── handle() — dispatch ────────────────────────────────────────────────────

  /**
   * Non-ACTION endpoints (CRUD, SELECTOR, …) must fall through so the default
   * pipeline can serve them. The handler returns {@code null} so the dispatcher
   * in {@link SalesQuotationHeaderHandler} continues with the next handler.
   */
  @Test
  public void testNonActionEndpointReturnsNull() {
    assertNull(new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .fieldName(ACTION_REJECT).recordId("q-1").build()));
  }

  /**
   * Actions with a {@code fieldName} other than {@code rejectQuotation} must
   * not be claimed by this handler — they belong to other handlers in the
   * dispatch chain (e.g. {@code cloneRecord}, {@code createDraftInvoice}).
   */
  @Test
  public void testActionWithUnknownFieldReturnsNull() {
    assertNull(new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").recordId("q-1").build()));
  }

  /**
   * The reject action only accepts POST. GET requests with the right
   * {@code fieldName} fall through so a hypothetical future GET handler
   * could pick them up without colliding with this one.
   */
  @Test
  public void testNonPostMethodReturnsNull() {
    assertNull(new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).recordId("q-1").build()));
  }

  // ── recordId validation ───────────────────────────────────────────────────

  /**
   * A missing {@code recordId} must produce HTTP 400 with a message that
   * mentions "Record ID" so the React modal can surface a localised error
   * to the user instead of crashing.
   */
  @Test
  public void testMissingRecordIdReturns400() throws JSONException {
    NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Record ID"));
  }

  /**
   * Whitespace-only {@code recordId} is treated the same as missing — the
   * handler must reject it before touching OBDal so we don't issue a
   * pointless lookup with a blank id.
   */
  @Test
  public void testBlankRecordIdReturns400() {
    NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).recordId("   ").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  // ── reason body validation ────────────────────────────────────────────────

  /**
   * A {@code null} request body (e.g. a POST without payload) must produce
   * HTTP 400 with a message mentioning "reason" — the React modal forces
   * the user to pick one before submitting, so a missing body always means
   * the caller is misusing the endpoint.
   */
  @Test
  public void testNullBodyReturns400() throws JSONException {
    NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).recordId("q-1").build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("reason"));
  }

  /**
   * A request body that is a valid but empty JSON object ({@code {}}) must
   * be rejected just like a {@code null} body — the {@code rejectReason}
   * key is mandatory.
   */
  @Test
  public void testEmptyBodyReturns400() throws JSONException {
    NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).recordId("q-1")
        .requestBody(new JSONObject()).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /**
   * A {@code rejectReason} key with an empty string must be rejected. We
   * never want to issue a {@code C_Reject_Reason} lookup with a blank id —
   * that would silently 404 inside OBDal.
   */
  @Test
  public void testBlankReasonReturns400() throws JSONException {
    NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_REJECT).recordId("q-1")
        .requestBody(new JSONObject().put("rejectReason", "")).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  // ── extractReasonId — direct unit test ────────────────────────────────────

  /**
   * The primary key the React modal sends is {@code rejectReason} (the entity
   * field name). That value must be returned verbatim.
   */
  @Test
  public void testExtractReasonIdReadsRejectReason() throws JSONException {
    JSONObject body = new JSONObject().put("rejectReason", "rr-123");
    assertEquals("rr-123", new RejectQuotationHandler().extractReasonId(body));
  }

  /**
   * Defensive fallback: callers that send the raw Etendo column name
   * ({@code C_Reject_Reason_ID}) must also work, so external integrations or
   * future tooling don't break if they use the column convention instead of
   * the camelCase entity field name.
   */
  @Test
  public void testExtractReasonIdFallsBackToColumnName() throws JSONException {
    JSONObject body = new JSONObject().put("C_Reject_Reason_ID", "rr-fallback");
    assertEquals("rr-fallback", new RejectQuotationHandler().extractReasonId(body));
  }

  /**
   * Whitespace-only values count as blank — extractor returns {@code null}
   * so the dispatch can short-circuit with a 400 instead of issuing a
   * lookup against a blank id.
   */
  @Test
  public void testExtractReasonIdReturnsNullForBlankString() throws JSONException {
    JSONObject body = new JSONObject().put("rejectReason", "   ");
    assertNull(new RejectQuotationHandler().extractReasonId(body));
  }

  /**
   * A {@code null} body is the most common "no body" shape from
   * {@link NeoContext#getRequestBody()}. The extractor must tolerate it
   * without NPE and return {@code null}.
   */
  @Test
  public void testExtractReasonIdReturnsNullForNullBody() {
    assertNull(new RejectQuotationHandler().extractReasonId(null));
  }

  // ── quotation lookup ──────────────────────────────────────────────────────

  /**
   * If OBDal cannot resolve the {@code recordId} to an {@link Order}, the
   * handler must respond 400 with a "not found" message and skip every side
   * effect — no save, no flush. Verifies {@code dal.save} is never called.
   */
  @Test
  public void testQuotationNotFoundReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), anyString())).thenReturn(null);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("not-found")
          .requestBody(new JSONObject().put("rejectReason", "rr-1")).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("not found"));
      verify(dal, never()).save(any());
    }
  }

  /**
   * Rejecting from any non-UE state (here {@code DR}) must be refused. The
   * error message must mention "Under Evaluation" so the UI can surface the
   * reason and the handler must NOT mutate the quotation.
   */
  @Test
  public void testQuotationNotInUEReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("DR");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-DR")
          .requestBody(new JSONObject().put("rejectReason", "rr-1")).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Under Evaluation"));
      verify(quotation, never()).setDocumentStatus(anyString());
    }
  }

  /**
   * Re-rejecting a quotation already in {@code CJ} must be refused — the
   * frontend hides the menu item, but a curl/Postman caller could still hit
   * the endpoint, and the handler is the ultimate gatekeeper.
   */
  @Test
  public void testQuotationInCJReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("CJ");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-CJ")
          .requestBody(new JSONObject().put("rejectReason", "rr-1")).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      verify(quotation, never()).setDocumentStatus(anyString());
    }
  }

  // ── reject reason lookup ──────────────────────────────────────────────────

  /**
   * Quotation is OK and in UE, but the supplied {@code rejectReason} id does
   * not resolve to a {@link RejectReason}. Must respond 400 with a "not
   * found" message and skip the mutation.
   */
  @Test
  public void testRejectReasonNotFoundReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("UE");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);
      when(dal.get(eq(RejectReason.class), anyString())).thenReturn(null);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-UE")
          .requestBody(new JSONObject().put("rejectReason", "rr-missing")).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("not found"));
      verify(quotation, never()).setDocumentStatus(anyString());
    }
  }

  /**
   * The reason id resolves but the row is inactive ({@code isactive='N'}).
   * The selector list filters those out, so receiving one means the caller
   * is using a stale id; the handler must refuse the operation.
   */
  @Test
  public void testInactiveRejectReasonReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("UE");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);
      RejectReason inactive = mock(RejectReason.class);
      when(inactive.isActive()).thenReturn(false);
      when(dal.get(eq(RejectReason.class), anyString())).thenReturn(inactive);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-UE")
          .requestBody(new JSONObject().put("rejectReason", "rr-inactive")).build());
      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      verify(quotation, never()).setDocumentStatus(anyString());
    }
  }

  // ── happy path ────────────────────────────────────────────────────────────

  /**
   * End-to-end happy path: quotation in UE, valid active reason. Verifies the
   * three persisted mutations (DocStatus → CJ, RejectReason set, Processed →
   * true), the {@code save + flush} pair, and the JSON response shape that
   * the React modal consumes ({@code id}, {@code documentNo},
   * {@code documentStatus}).
   */
  @Test
  public void testHappyPathSetsCJAndPersistsAndReturns200() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("UE", "CJ");
      when(quotation.getId()).thenReturn("q-id-42");
      when(quotation.getDocumentNo()).thenReturn("QU-00042");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);

      RejectReason reason = mock(RejectReason.class);
      when(reason.isActive()).thenReturn(true);
      when(dal.get(eq(RejectReason.class), anyString())).thenReturn(reason);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-id-42")
          .requestBody(new JSONObject().put("rejectReason", "rr-priceTooHigh")).build());

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());

      verify(quotation).setRejectReason(reason);
      verify(quotation).setDocumentStatus("CJ");
      verify(quotation).setProcessed(true);
      verify(dal).save(quotation);
      verify(dal).flush();

      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("q-id-42", data.getString("id"));
      assertEquals("QU-00042", data.getString("documentNo"));
      assertEquals("CJ", data.getString("documentStatus"));
    }
  }

  /**
   * Same happy path but the body uses the column-name fallback key
   * ({@code C_Reject_Reason_ID}) instead of {@code rejectReason}. Confirms
   * the defensive parsing path also drives the full mutation correctly.
   */
  @Test
  public void testHappyPathAcceptsColumnNameFallback() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn("UE");
      when(quotation.getId()).thenReturn("q-id-9");
      when(quotation.getDocumentNo()).thenReturn("QU-9");
      when(dal.get(eq(Order.class), anyString())).thenReturn(quotation);

      RejectReason reason = mock(RejectReason.class);
      when(reason.isActive()).thenReturn(true);
      when(dal.get(eq(RejectReason.class), anyString())).thenReturn(reason);

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-id-9")
          .requestBody(new JSONObject().put("C_Reject_Reason_ID", "rr-fallback")).build());

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      verify(quotation).setRejectReason(reason);
    }
  }

  // ── error envelope ────────────────────────────────────────────────────────

  /**
   * Unexpected runtime exceptions inside the persistence layer must be
   * swallowed at the handler boundary and surfaced as HTTP 500 — never as
   * a stack-trace leaking into the response. Validation failures stay as
   * 400 (those go through {@link OBException}); only truly unexpected
   * faults bubble up as 500.
   */
  @Test
  public void testRuntimeExceptionReturns500() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Order.class), anyString())).thenThrow(new RuntimeException("DB exploded"));

      NeoResponse r = new RejectQuotationHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_REJECT).recordId("q-1")
          .requestBody(new JSONObject().put("rejectReason", "rr-1")).build());
      assertNotNull(r);
      assertEquals(500, r.getHttpStatus());
    }
  }
}
