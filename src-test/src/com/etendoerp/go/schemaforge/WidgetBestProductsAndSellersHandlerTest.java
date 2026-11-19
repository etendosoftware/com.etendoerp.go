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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link WidgetBestProductsHandler} and {@link WidgetBestSellersHandler}, two copies
 * of the same widget that rank by revenue and by quantity. Covers: method guard (non-GET), empty
 * result, null trendPct (absent from JSON), negative trendPct after COALESCE fix (present in JSON
 * → trend badge renders) and the range parameter, on both handlers; and each one's row mapping.
 *
 * @covers com.etendoerp.go.schemaforge.WidgetBestProductsHandler
 * @covers com.etendoerp.go.schemaforge.WidgetBestSellersHandler
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetBestProductsAndSellersHandlerTest {

  /**
   * One widget under test.
   *
   * @param handler    builds the handler
   * @param entityName the dashboard entity it serves
   * @param row        a query row of the widget with the given trend_pct
   */
  record Widget(Supplier<NeoHandler> handler, String entityName,
      Function<BigDecimal, Object[]> row) {
    @Override
    public String toString() {
      return entityName;
    }
  }

  static Stream<Widget> widgets() {
    return Stream.of(
        new Widget(WidgetBestProductsHandler::new, "best-products",
            trend -> new Object[] { "prod-id", "Product A", new BigDecimal("10"),
                new BigDecimal("500.00"), trend }),
        new Widget(WidgetBestSellersHandler::new, "best-sellers",
            trend -> new Object[] { "seller-id", "Seller A", new BigDecimal("42"), "Units",
                trend }));
  }

  @Mock private OBContext obContext;
  @Mock private Client client;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<WidgetQueryHelper> queryHelperMock;

  // ETP-5088 — every widget now resolves the caller's role and gates on AD_Window_Access before
  // querying. These suites cover the widget's own behaviour, so they grant access and let the
  // real WidgetAccessPolicy run on top of a mocked NeoAccessHelper; the gate itself is covered by
  // WidgetAccessPolicyTest.
  @Mock private Role role;
  private MockedStatic<NeoAccessHelper> accessHelperMock;

  @BeforeEach
  void setUp() {
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    accessHelperMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(role);
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
        .thenReturn(true);
    obContextMock = mockStatic(OBContext.class);
    queryHelperMock = mockStatic(WidgetQueryHelper.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
  }

  @AfterEach
  void tearDown() {
    accessHelperMock.close();
    obContextMock.close();
    queryHelperMock.close();
  }

  private static NeoContext context(Widget widget, String method) {
    return NeoContext.builder()
        .specName("dashboard").entityName(widget.entityName())
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /** Stubs the query to answer {@code rows} and lets the real response builder run. */
  private void queryAnswers(List<Object[]> rows) {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(rows);
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();
  }

  private static JSONObject firstItem(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  /** Verifies that POST requests are rejected with HTTP 405. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleRejectsPost(Widget widget) {
    assertEquals(405, widget.handler().get().handle(context(widget, "POST")).getHttpStatus());
  }

  /** Verifies that PUT requests are rejected with HTTP 405. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleRejectsPut(Widget widget) {
    assertEquals(405, widget.handler().get().handle(context(widget, "PUT")).getHttpStatus());
  }

  /** Verifies that an empty query result returns HTTP 200. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleEmptyResultReturns200(Widget widget) {
    queryAnswers(Collections.emptyList());

    NeoResponse response = widget.handler().get().handle(context(widget, "GET"));
    assertEquals(200, response.getHttpStatus());
  }

  /** Verifies that an empty query result returns a response with an empty data array. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleEmptyResultReturnsEmptyDataArray(Widget widget) throws Exception {
    queryAnswers(Collections.emptyList());

    NeoResponse response = widget.handler().get().handle(context(widget, "GET"));
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length());
  }

  /**
   * Verifies that when the SQL returns NULL for trend_pct (no previous period data),
   * the trendPct field is absent from the response item so the badge is not rendered.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleRowWithNullTrendPctIsAbsentFromItem(Widget widget) throws Exception {
    queryAnswers(Collections.singletonList(widget.row().apply(null)));

    JSONObject item = firstItem(widget.handler().get().handle(context(widget, "GET")));

    assertFalse(item.has("trendPct"), "trendPct must be absent when SQL returns NULL");
  }

  /**
   * Verifies that after the COALESCE fix, when a product or seller has no current-period sales,
   * trendPct is -100 and is included in the response so the trend badge renders correctly.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleRowWithNegativeTrendPctIsPresentInItem(Widget widget) throws Exception {
    queryAnswers(Collections.singletonList(widget.row().apply(new BigDecimal("-100"))));

    JSONObject item = firstItem(widget.handler().get().handle(context(widget, "GET")));

    assertTrue(item.has("trendPct"), "trendPct must be present when COALESCE returns -100");
    assertEquals(-100, item.getInt("trendPct"));
  }

  /**
   * Regression test for ETP-4521: verifies that a GET request with {@code range=last30d}
   * propagates that value to {@code WidgetQueryHelper.resolveQuery} so the trend %
   * comparison respects the selected date range instead of a hardcoded current month.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("widgets")
  void testHandleWithRangeParamPassesRangeToResolveQuery(Widget widget) {
    queryAnswers(Collections.emptyList());
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName(widget.entityName())
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .queryParams(Map.of("range", "last30d"))
        .build();

    widget.handler().get().handle(ctx);

    queryHelperMock.verify(() -> WidgetQueryHelper.resolveQuery(
        any(), any(), any(), org.mockito.ArgumentMatchers.eq("last30d")));
  }

  /** Best products: id, name, qty, amount and trendPct are mapped to the response item. */
  @Test
  void testBestProductsRowFieldsAreCorrectlyMapped() throws Exception {
    Widget widget = widgets().findFirst().orElseThrow();
    queryAnswers(Collections.singletonList(new Object[] { "prod-id", "Product X",
        new BigDecimal("8"), new BigDecimal("1200.50"), new BigDecimal("15") }));

    JSONObject item = firstItem(widget.handler().get().handle(context(widget, "GET")));

    assertEquals("prod-id", item.getString("id"));
    assertEquals("Product X", item.getString("name"));
    assertEquals(8L, item.getLong("qty"));
    assertEquals(1200.50, item.getDouble("amount"), 0.001);
    assertEquals(15, item.getInt("trendPct"));
  }

  /** Best sellers: id, name, qty, uom and trendPct are mapped to the response item. */
  @Test
  void testBestSellersRowFieldsAreCorrectlyMapped() throws Exception {
    Widget widget = widgets().skip(1).findFirst().orElseThrow();
    queryAnswers(Collections.singletonList(new Object[] { "seller-id", "Product X",
        new BigDecimal("15"), "Kg", new BigDecimal("25") }));

    JSONObject item = firstItem(widget.handler().get().handle(context(widget, "GET")));

    assertEquals("seller-id", item.getString("id"));
    assertEquals("Product X", item.getString("name"));
    assertEquals(15L, item.getLong("qty"));
    assertEquals("Kg", item.getString("uom"));
    assertEquals(25, item.getInt("trendPct"));
  }
}
