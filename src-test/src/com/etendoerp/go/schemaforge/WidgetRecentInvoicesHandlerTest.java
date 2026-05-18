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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

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
 * Unit tests for {@link WidgetRecentInvoicesHandler}.
 * Covers all branches: method guard, range parameter routing, row mapping (happy path and nulls),
 * empty result, and exception handling. Static dependencies (OBContext, WidgetQueryHelper)
 * are isolated with Mockito {@code MockedStatic} so no DB or CDI container is required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetRecentInvoicesHandlerTest {

  private WidgetRecentInvoicesHandler handler;

  @Mock
  private OBContext obContext;
  @Mock
  private Client client;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<WidgetQueryHelper> queryHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetRecentInvoicesHandler();
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

  // ---------------------------------------------------------------------------
  // Helper to build a GET context with optional query params
  // ---------------------------------------------------------------------------

  private NeoContext buildGetContext(Map<String, String> params) {
    return NeoContext.builder().httpMethod("GET").specName("dashboard").entityName("recent-invoices").queryParams(
        params).build();
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that any non-GET HTTP method is rejected immediately with HTTP 405
   * and that {@code WidgetQueryHelper.resolveQuery} is never invoked.
   */
  @Test
  void nonGetMethodReturns405() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").specName("dashboard").entityName(
        "recent-invoices").build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(405, response.getHttpStatus());
    queryHelperMock.verify(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()), never());
  }

  /**
   * Verifies that a GET request with {@code range=last30d} passes that value as the last
   * argument to {@code WidgetQueryHelper.resolveQuery} so the ranged SQL path is taken.
   */
  @Test
  void getWithRangeParamPassesRangeToQuery() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenReturn(
        Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any())).thenCallRealMethod();

    NeoContext ctx = buildGetContext(Map.of("range", "last30d"));
    handler.handle(ctx);

    queryHelperMock.verify(
        () -> WidgetQueryHelper.resolveQuery(any(), any(), any(), org.mockito.ArgumentMatchers.eq("last30d")));
  }

  /**
   * Verifies that when {@code context.getQueryParams()} returns null the handler passes null
   * as the range argument to {@code resolveQuery}, triggering the fallback SQL path.
   */
  @Test
  void getWithNullQueryParamsPassesNullRange() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenReturn(
        Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any())).thenCallRealMethod();

    NeoContext ctx = buildGetContext(null);
    handler.handle(ctx);

    queryHelperMock.verify(
        () -> WidgetQueryHelper.resolveQuery(any(), any(), any(), org.mockito.ArgumentMatchers.isNull()));
  }

  /**
   * Verifies that when the query returns an empty list the response has HTTP 200
   * and {@code response.count == 0}.
   */
  @Test
  void getWithEmptyRowsReturnsEmptyDataArray() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenReturn(
        Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any())).thenCallRealMethod();

    NeoResponse response = handler.handle(buildGetContext(null));

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
    assertEquals(0, responseBody.getJSONArray("data").length());
  }

  /**
   * Verifies that a row with all non-null values is mapped correctly to the JSON item,
   * including the navigation sub-object pointing to the sales-invoice window.
   */
  @Test
  void getWithRowsMapsAllFieldsCorrectly() throws Exception {
    Object[] row = { "INV-001", "DOC-001", "Acme Corp", "01-05-2026", BigDecimal.valueOf(1500.00), "CO" };

    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenReturn(
        Collections.singletonList(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any())).thenCallRealMethod();

    NeoResponse response = handler.handle(buildGetContext(null));

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());

    JSONObject item = data.getJSONObject(0);
    assertEquals("INV-001", item.getString("id"));
    assertEquals("DOC-001", item.getString("documentNo"));
    assertEquals("Acme Corp", item.getString("client"));
    assertEquals("01-05-2026", item.getString("date"));
    assertEquals(1500.0, item.getDouble("amount"), 0.001);
    assertEquals("CO", item.getString("status"));

    JSONObject navigation = item.getJSONObject("navigation");
    assertEquals("sales-invoice", navigation.getString("window"));
    assertEquals("INV-001", navigation.getString("recordId"));
    assertEquals("record", navigation.getString("type"));
  }

  /**
   * Verifies that null values in row positions [1..5] default to empty string for text fields
   * and to 0 for the amount field, so the frontend never receives JSON null values.
   */
  @Test
  void getWithNullFieldsInRowDefaultsToEmptyStringAndZero() throws Exception {
    Object[] row = { "INV-NULL", null, null, null, null, null };

    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenReturn(
        Collections.singletonList(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any())).thenCallRealMethod();

    NeoResponse response = handler.handle(buildGetContext(null));

    JSONObject item = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertEquals("", item.getString("documentNo"));
    assertEquals("", item.getString("client"));
    assertEquals("", item.getString("date"));
    assertEquals(0, item.getInt("amount"));
    assertEquals("", item.getString("status"));
  }

  /**
   * Verifies that an exception thrown by {@code resolveQuery} (e.g. a DB failure) is caught
   * and results in an HTTP 500 response without propagating the exception to the caller.
   */
  @Test
  void exceptionFromQueryReturns500() {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any())).thenThrow(
        new RuntimeException("DB error"));

    NeoResponse response = handler.handle(buildGetContext(null));

    assertEquals(500, response.getHttpStatus());
  }
}
