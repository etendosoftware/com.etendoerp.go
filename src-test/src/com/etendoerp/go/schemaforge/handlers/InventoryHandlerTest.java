/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;

/** Tests for {@link InventoryHandler} — the {@code generateLines} action on the inventory entity. */
public class InventoryHandlerTest {

  private static final String PROCESS_ID = "105";
  private static final String RECORD_ID = "inv-1";
  private static final String WAREHOUSE_ID = "wh-1";

  private final InventoryHandler handler = new InventoryHandler();

  /** Builds a mocked NeoContext. */
  private NeoContext buildContext(NeoEndpointType type, String fieldName, String recordId,
      JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(type);
    when(ctx.getFieldName()).thenReturn(fieldName);
    when(ctx.getRecordId()).thenReturn(recordId);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /**
   * Wires OBDal so the inventory lookup returns a record with a warehouse and process 105 resolves.
   * Call this into a local variable BEFORE opening {@code mockStatic(OBDal.class)} — never inline as
   * the argument of {@code dalMock.when(...).thenReturn(...)}, or the regular stubs it performs run
   * while the static stubbing is still open (Mockito UnfinishedStubbingException).
   */
  private OBDal happyPathObDal() {
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(WAREHOUSE_ID);

    InventoryCount inventory = mock(InventoryCount.class);
    when(inventory.getWarehouse()).thenReturn(warehouse);

    Process process = mock(Process.class);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(InventoryCount.class, RECORD_ID)).thenReturn(inventory);
    when(obDal.get(Process.class, PROCESS_ID)).thenReturn(process);
    return obDal;
  }

  // ---------------------------------------------------------------------------
  // Guard clauses (no backend interaction)
  // ---------------------------------------------------------------------------

  @Test
  public void nonActionEndpointReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "generateLines", RECORD_ID, null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void wrongActionNameReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "somethingElse", RECORD_ID, null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void nullRecordIdReturns400() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", null, null);
    NeoResponse resp = handler.handle(ctx);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void blankRecordIdReturns400() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", "   ", null);
    NeoResponse resp = handler.handle(ctx);
    assertEquals(400, resp.getHttpStatus());
  }

  // ---------------------------------------------------------------------------
  // Record / warehouse / process resolution
  // ---------------------------------------------------------------------------

  @Test
  public void inventoryNotFoundReturns404() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(InventoryCount.class, RECORD_ID)).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse resp = handler.handle(ctx);
      assertEquals(404, resp.getHttpStatus());
    }
  }

  @Test
  public void noWarehouseReturns400() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    InventoryCount inventory = mock(InventoryCount.class);
    when(inventory.getWarehouse()).thenReturn(null);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(InventoryCount.class, RECORD_ID)).thenReturn(inventory);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse resp = handler.handle(ctx);
      assertEquals(400, resp.getHttpStatus());
    }
  }

  @Test
  public void invalidQtyRangeReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("QtyRange", "X");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, body);
    OBDal obDal = happyPathObDal();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse resp = handler.handle(ctx);
      assertEquals(400, resp.getHttpStatus());
    }
  }

  @Test
  public void processNotFoundReturns500() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(WAREHOUSE_ID);
    InventoryCount inventory = mock(InventoryCount.class);
    when(inventory.getWarehouse()).thenReturn(warehouse);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(InventoryCount.class, RECORD_ID)).thenReturn(inventory);
    when(obDal.get(Process.class, PROCESS_ID)).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse resp = handler.handle(ctx);
      assertEquals(500, resp.getHttpStatus());
    }
  }

  @Test
  public void unexpectedExceptionReturns500() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(InventoryCount.class, RECORD_ID)).thenThrow(new RuntimeException("db error"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse resp = handler.handle(ctx);
      assertEquals(500, resp.getHttpStatus());
    }
  }

  // ---------------------------------------------------------------------------
  // Happy paths + parameter shaping
  // ---------------------------------------------------------------------------

  @Test
  public void nullBodyGeneratesWithDefaultsAndNoCategory() throws Exception {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    OBDal obDal = happyPathObDal();
    NeoResponse sentinel = new NeoResponse(200, new JSONObject());

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> procMock = mockStatic(NeoProcessService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      procMock.when(() -> NeoProcessService.executeProcess(any(Process.class), any(JSONObject.class)))
          .thenReturn(sentinel);

      NeoResponse resp = handler.handle(ctx);
      assertSame(sentinel, resp);

      ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
      procMock.verify(() -> NeoProcessService.executeProcess(any(Process.class), captor.capture()));
      JSONObject params = captor.getValue();
      assertEquals(RECORD_ID, params.getString("recordId"));
      assertEquals(WAREHOUSE_ID, params.getString("M_Warehouse_ID"));
      assertEquals("%", params.getString("ProductValue"));
      assertEquals("N", params.getString("QtyRange"));
      assertEquals("N", params.getString("regularization"));
      assertFalse("no category filter when body is null", params.has("M_Product_Category_ID"));
    }
  }

  @Test
  public void selectedCategoryAndFlagsForwardedToProcess() throws Exception {
    JSONObject body = new JSONObject();
    body.put("M_Product_Category_ID", "CAT1");
    body.put("QtyRange", ">");
    body.put("regularization", "Y");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, body);
    OBDal obDal = happyPathObDal();
    NeoResponse sentinel = new NeoResponse(200, new JSONObject());

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> procMock = mockStatic(NeoProcessService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      procMock.when(() -> NeoProcessService.executeProcess(any(Process.class), any(JSONObject.class)))
          .thenReturn(sentinel);

      handler.handle(ctx);

      ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
      procMock.verify(() -> NeoProcessService.executeProcess(any(Process.class), captor.capture()));
      JSONObject params = captor.getValue();
      assertEquals("CAT1", params.getString("M_Product_Category_ID"));
      assertEquals(">", params.getString("QtyRange"));
      assertEquals("Y", params.getString("regularization"));
    }
  }

  /** Regression guard: the literal string "null" must be treated as "no category filter". */
  @Test
  public void literalNullCategoryStringIsOmitted() throws Exception {
    JSONObject body = new JSONObject();
    body.put("M_Product_Category_ID", "null");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, body);
    OBDal obDal = happyPathObDal();
    NeoResponse sentinel = new NeoResponse(200, new JSONObject());

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> procMock = mockStatic(NeoProcessService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      procMock.when(() -> NeoProcessService.executeProcess(any(Process.class), any(JSONObject.class)))
          .thenReturn(sentinel);

      handler.handle(ctx);

      ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
      procMock.verify(() -> NeoProcessService.executeProcess(any(Process.class), captor.capture()));
      assertFalse(captor.getValue().has("M_Product_Category_ID"));
    }
  }

  /** Blank category and blank QtyRange fall back to "all categories" and the default range. */
  @Test
  public void blankCategoryOmittedAndBlankQtyRangeDefaultsToN() throws Exception {
    JSONObject body = new JSONObject();
    body.put("M_Product_Category_ID", "");
    body.put("QtyRange", "");
    body.put("regularization", "somethingUnexpected");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, body);
    OBDal obDal = happyPathObDal();
    NeoResponse sentinel = new NeoResponse(200, new JSONObject());

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> procMock = mockStatic(NeoProcessService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      procMock.when(() -> NeoProcessService.executeProcess(any(Process.class), any(JSONObject.class)))
          .thenReturn(sentinel);

      handler.handle(ctx);

      ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
      procMock.verify(() -> NeoProcessService.executeProcess(any(Process.class), captor.capture()));
      JSONObject params = captor.getValue();
      assertFalse(params.has("M_Product_Category_ID"));
      assertEquals("N", params.getString("QtyRange"));
      // Any value other than "Y" collapses to "N".
      assertEquals("N", params.getString("regularization"));
    }
  }

  @Test
  public void eachValidQtyRangeCodeIsAccepted() throws Exception {
    for (String code : new String[] {"<", ">", "=", "N"}) {
      JSONObject body = new JSONObject();
      body.put("QtyRange", code);
      NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, body);
      OBDal obDal = happyPathObDal();
      NeoResponse sentinel = new NeoResponse(200, new JSONObject());

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<NeoProcessService> procMock = mockStatic(NeoProcessService.class)) {
        dalMock.when(OBDal::getInstance).thenReturn(obDal);
        procMock.when(() -> NeoProcessService.executeProcess(any(Process.class), any(JSONObject.class)))
            .thenReturn(sentinel);

        NeoResponse resp = handler.handle(ctx);
        assertSame("QtyRange code '" + code + "' should be accepted", sentinel, resp);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        procMock.verify(() -> NeoProcessService.executeProcess(any(Process.class), captor.capture()));
        assertEquals(code, captor.getValue().getString("QtyRange"));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle + annotation
  // ---------------------------------------------------------------------------

  @Test
  public void afterHandleReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "generateLines", RECORD_ID, null);
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void handlerIsNamedInventory() {
    javax.inject.Named named = InventoryHandler.class.getAnnotation(javax.inject.Named.class);
    assertTrue("handler must be @Named", named != null);
    assertEquals("inventory", named.value());
  }
}
