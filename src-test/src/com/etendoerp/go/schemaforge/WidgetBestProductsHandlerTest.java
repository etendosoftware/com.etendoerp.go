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

  @Test
  void testHandleRejectsPost() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-products")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  @Test
  void testHandleRejectsPut() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-products")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  @Test
  void testHandle_emptyResult_returns200() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    assertEquals(200, response.getHttpStatus());
  }

  @Test
  void testHandle_emptyResult_returnsEmptyDataArray() throws Exception {
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length());
  }

  @Test
  void testHandle_rowWithNullTrendPct_trendPctAbsentFromItem() throws Exception {
    // SQL returns null for trend_pct when prev_period has no data
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

  @Test
  void testHandle_rowWithNegativeTrendPct_trendPctPresentInItem() throws Exception {
    // After COALESCE fix: curr_period amount=0, prev_period amount>0 → trendPct = -100
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

  @Test
  void testHandle_rowFields_areCorrectlyMapped() throws Exception {
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
