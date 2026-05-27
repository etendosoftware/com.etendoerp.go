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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Unit tests for {@link WidgetTopClientsHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetTopClientsHandlerTest {

  private WidgetTopClientsHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<WidgetQueryHelper> queryHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetTopClientsHandler();
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

  @Nested
  @DisplayName("Method guard")
  class MethodGuard {
    @Test
    void rejectsPost() {
      NeoContext ctx = NeoContext.builder()
          .specName("dashboard").entityName("top-clients")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
      assertEquals(405, handler.handle(ctx).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void emptyResultReturns200() throws Exception {
      queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
          .thenReturn(Collections.emptyList());
      queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
          .thenCallRealMethod();

      NeoContext ctx = NeoContext.builder()
          .specName("dashboard").entityName("top-clients")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

      NeoResponse response = handler.handle(ctx);
      assertEquals(200, response.getHttpStatus());
    }

    @Test
    void singleRowMapsNameAndTotal() throws Exception {
      Object[] row = { "Big Client Inc.", new BigDecimal("50000.00") };
      queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
          .thenReturn(Collections.singletonList(row));
      queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
          .thenCallRealMethod();

      NeoContext ctx = NeoContext.builder()
          .specName("dashboard").entityName("top-clients")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

      NeoResponse response = handler.handle(ctx);
      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Big Client Inc.", item.getString("name"));
      assertEquals(50000.00, item.getDouble("total"), 0.01);
    }

    @Test
    void rangeParamPassedToHelper() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("range", "last30d");

      queryHelperMock.when(() -> WidgetQueryHelper.resolveQuery(any(), any(), any(), any()))
          .thenReturn(Collections.emptyList());
      queryHelperMock.when(() -> WidgetQueryHelper.buildDataResponse(any()))
          .thenCallRealMethod();

      NeoContext ctx = NeoContext.builder()
          .specName("dashboard").entityName("top-clients")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .queryParams(params).build();

      NeoResponse response = handler.handle(ctx);
      assertEquals(200, response.getHttpStatus());
    }
  }
}
