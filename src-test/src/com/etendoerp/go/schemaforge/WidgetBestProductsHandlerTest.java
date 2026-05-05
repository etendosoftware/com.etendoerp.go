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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for {@link WidgetBestProductsHandler}.
 * Covers: method guard (non-GET), empty result, null trendPct (absent from JSON),
 * and negative trendPct after COALESCE fix (present in JSON → trend badge renders).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetBestProductsHandlerTest {

  private WidgetBestProductsHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<WidgetQueryHelper> queryHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetBestProductsHandler();
    obContextMock = mockStatic(OBContext.class);
    queryHelperMock = mockStatic(WidgetQueryHelper.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    queryHelperMock.close();
  }

  private NeoContext getContext() {
    return NeoContext.builder()
        .specName("dashboard").entityName("best-products")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /** Verifies that POST requests are rejected with HTTP 405. */
  @Test
  void testHandleRejectsPost() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-products")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  /** Verifies that PUT requests are rejected with HTTP 405. */
  @Test
  void testHandleRejectsPut() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-products")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  /** Verifies that an empty query result returns HTTP 200. */
  @Test
  void testHandleEmptyResultReturns200() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    assertEquals(200, response.getHttpStatus());
  }

  /** Verifies that an empty query result returns a response with an empty data array. */
  @Test
  void testHandleEmptyResultReturnsEmptyDataArray() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length());
  }

  /**
   * Verifies that when the SQL returns NULL for trend_pct (no previous period data),
   * the trendPct field is absent from the response item so the badge is not rendered.
   */
  @Test
  void testHandleRowWithNullTrendPctIsAbsentFromItem() throws Exception {
    Object[] row = { "prod-id", "Product A", new BigDecimal("10"), new BigDecimal("500.00"), null };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.singletonList(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONObject item = response.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertFalse(item.has("trendPct"), "trendPct must be absent when SQL returns NULL");
  }

  /**
   * Verifies that after the COALESCE fix, when a product has no current-period sales,
   * trendPct is -100 and is included in the response so the trend badge renders correctly.
   */
  @Test
  void testHandleRowWithNegativeTrendPctIsPresentInItem() throws Exception {
    Object[] row = { "prod-id", "Product A", new BigDecimal("10"), new BigDecimal("500.00"), new BigDecimal("-100") };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.singletonList(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONObject item = response.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertTrue(item.has("trendPct"), "trendPct must be present when COALESCE returns -100");
    assertEquals(-100, item.getInt("trendPct"));
  }

  /** Verifies that all row fields (id, name, qty, amount, trendPct) are correctly mapped to the response item. */
  @Test
  void testHandleRowFieldsAreCorrectlyMapped() throws Exception {
    Object[] row = { "prod-id", "Product X", new BigDecimal("8"), new BigDecimal("1200.50"), new BigDecimal("15") };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.singletonList(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONObject item = response.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertEquals("prod-id", item.getString("id"));
    assertEquals("Product X", item.getString("name"));
    assertEquals(8L, item.getLong("qty"));
    assertEquals(1200.50, item.getDouble("amount"), 0.001);
    assertEquals(15, item.getInt("trendPct"));
  }
}
