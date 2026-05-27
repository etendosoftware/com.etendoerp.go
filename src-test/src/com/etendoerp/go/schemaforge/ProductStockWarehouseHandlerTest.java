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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/** Tests for {@link ProductStockWarehouseHandler}. */
public class ProductStockWarehouseHandlerTest {

  private final ProductStockWarehouseHandler handler = new ProductStockWarehouseHandler();

  private NeoContext buildContext(NeoEndpointType type, String method,
      String recordId, Map<String, String> queryParams) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(type);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getRecordId()).thenReturn(recordId);
    when(ctx.getQueryParams()).thenReturn(queryParams);
    return ctx;
  }

  @Test
  public void nonCrudEndpointReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "GET", null, null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void nonGetMethodReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "POST", null, null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void singleRecordGetReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "GET", "record-1", null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void blankParentIdReturnsNull() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "");
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "GET", null, params);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void noQueryParamsReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "GET", null, null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void successfulStockQueryReturnsResponse() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "product-1");
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "GET", null, params);

    List<Object[]> rows = new ArrayList<>();
    rows.add(new Object[]{
        "sd-1", "loc-1", "BIN-A", "wh-1", "Main Warehouse",
        new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("1.00"), "0"
    });

    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(query.list()).thenReturn(rows);

    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = handler.handle(ctx);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void exceptionReturns500() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "product-1");
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "GET", null, params);

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("db error"));
    when(obDal.getSession()).thenReturn(session);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = handler.handle(ctx);
      assertEquals(500, resp.getHttpStatus());
    }
  }
}
