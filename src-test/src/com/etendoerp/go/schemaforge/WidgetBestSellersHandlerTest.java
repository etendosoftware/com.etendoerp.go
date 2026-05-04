package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

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
 * Unit tests for {@link WidgetBestSellersHandler}.
 * Covers: method guard (non-GET), empty result, null trendPct (absent from JSON),
 * and negative trendPct after COALESCE fix (present in JSON → trend badge renders).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetBestSellersHandlerTest {

  private WidgetBestSellersHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<WidgetQueryHelper> queryHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetBestSellersHandler();
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
        .specName("dashboard").entityName("best-sellers")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .build();
  }

  @Test
  void testHandleRejectsPost() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-sellers")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  @Test
  void testHandleRejectsPut() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("best-sellers")
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
    Object[] row = { "seller-id", "Seller A", new BigDecimal("42"), "Units", null };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(List.of(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONObject item = response.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertFalse(item.has("trendPct"), "trendPct must be absent when SQL returns NULL");
  }

  @Test
  void testHandle_rowWithNegativeTrendPct_trendPctPresentInItem() throws Exception {
    // After COALESCE fix: curr_period qty=0, prev_period qty=5 → trendPct = -100
    Object[] row = { "seller-id", "Seller A", new BigDecimal("5"), "Units", new BigDecimal("-100") };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(List.of(row));
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
    Object[] row = { "seller-id", "Product X", new BigDecimal("15"), "Kg", new BigDecimal("25") };
    queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
        .thenReturn(List.of(row));
    queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
        .thenCallRealMethod();

    NeoResponse response = handler.handle(getContext());
    JSONObject item = response.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertEquals("seller-id", item.getString("id"));
    assertEquals("Product X", item.getString("name"));
    assertEquals(15L, item.getLong("qty"));
    assertEquals("Kg", item.getString("uom"));
    assertEquals(25, item.getInt("trendPct"));
  }
}
