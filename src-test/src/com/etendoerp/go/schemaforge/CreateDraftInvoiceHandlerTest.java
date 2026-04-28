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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link CreateDraftInvoiceHandler} dispatch contract.
 * Covers routing decisions reachable without a CDI/DAL context — paths that
 * touch OBDal/OBContext (handleCreate, handleCheck, handleList business logic)
 * are validated via the smoke test plan documented in
 * docs/generated-custom-windows/sales-quotation.md.
 */
public class CreateDraftInvoiceHandlerTest {

  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_CREATE_DRAFT_INVOICE = "createDraftInvoice";

  /**
   * Non-ACTION endpoints (CRUD, SELECTOR, etc.) must pass through to the default
   * service — handler returns {@code null}.
   */
  @Test
  public void testNonActionEndpointReturnsNull() {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    assertNull(handler.handle(ctx));
  }

  /**
   * ACTION endpoint with an unknown fieldName (not createDraftInvoice/check/list)
   * must pass through — handler returns {@code null}.
   */
  @Test
  public void testActionWithUnknownFieldReturnsNull() {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherAction")
        .recordId("abc")
        .build();

    assertNull(handler.handle(ctx));
  }

  /**
   * createDraftInvoice only accepts POST. A GET on the same field must
   * not be handled here — the handler returns {@code null}.
   */
  @Test
  public void testCreateDraftInvoiceRejectsNonPostMethod() {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_DRAFT_INVOICE)
        .recordId("abc")
        .build();

    assertNull(handler.handle(ctx));
  }

  /**
   * createDraftInvoice with a blank recordId must return HTTP 400 immediately,
   * before reaching any DB call.
   */
  @Test
  public void testMissingRecordIdReturns400() throws JSONException {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_DRAFT_INVOICE)
        // no recordId
        .build();

    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertTrue(error.getString("message").contains("Record ID"));
  }

  /**
   * Same as the missing recordId case but with an explicitly empty string.
   */
  @Test
  public void testBlankRecordIdReturns400() throws JSONException {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_DRAFT_INVOICE)
        .recordId("   ")
        .build();

    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * The handler is a pre-hook only: afterHandle must always return null
   * (NeoHandler default behaviour).
   */
  @Test
  public void testAfterHandleReturnsNull() {
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();

    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION)
        .entityName(ENTITY_HEADER)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_DRAFT_INVOICE)
        .recordId("abc")
        .build();

    assertNull(handler.afterHandle(ctx));
  }
}
