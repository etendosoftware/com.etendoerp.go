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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link GoodsShipmentHeaderHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — routes ACTION requests to the right downstream handler
 *       (create draft invoice / clone record) or returns null when none matches.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 * </ul>
 *
 * <p>Tests that require DB access (invoiceStatus computation, issuerOrg enrichment)
 * are not included here — those are covered by integration tests.
 */
public class GoodsShipmentHeaderHandlerTest {

  /**
   * Creates a {@link GoodsShipmentHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   */
  private static GoodsShipmentHeaderHandler handlerWithMocks(
      CreateDraftInvoiceHandler mockCreateDraftInvoice,
      NeoCloneRecordHandler mockClone) throws Exception {
    GoodsShipmentHeaderHandler handler = new GoodsShipmentHeaderHandler();
    Field invoiceField = GoodsShipmentHeaderHandler.class.getDeclaredField("createDraftInvoiceHandler");
    invoiceField.setAccessible(true);
    invoiceField.set(handler, mockCreateDraftInvoice);
    Field cloneField = GoodsShipmentHeaderHandler.class.getDeclaredField("neoCloneRecordHandler");
    cloneField.setAccessible(true);
    cloneField.set(handler, mockClone);
    return handler;
  }

  /**
   * Builds a GET/CRUD {@link NeoContext} targeting the goods-shipment header entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  /**
   * Verifies that handle returns the create-draft-invoice response when that handler matches.
   */
  @Test
  public void testHandleDispatchesToCreateDraftInvoiceHandler() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createDraftInvoice")
        .build();
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "createDraftInvoice"));
    when(mockInvoice.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns the clone response when the clone handler matches
   * and the invoice handler returns null first.
   */
  @Test
  public void testHandleDispatchesToNeoCloneRecordHandler() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("cloneRecord")
        .build();
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "cloneRecord"));
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }
}
