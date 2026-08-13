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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
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
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for {@link ContactsBpStatsHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactsBpStatsHandlerTest {

  private ContactsBpStatsHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new ContactsBpStatsHandler();
    obContextMock = mockStatic(OBContext.class);
    obDalMock = mockStatic(OBDal.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    obDalMock.close();
  }

  private NeoContext buildContext(String method, Map<String, String> params) {
    return NeoContext.builder()
        .specName("contacts").entityName("bp-stats")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();
  }

  @Nested
  @DisplayName("Method guard")
  class MethodGuard {
    @Test
    void rejectsPost() {
      assertEquals(405, handler.handle(buildContext("POST", new HashMap<>())).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("Parameter validation")
  class ParameterValidation {
    @Test
    void missingBusinessPartnerIdReturns400() {
      NeoResponse response = handler.handle(buildContext("GET", new HashMap<>()));
      assertEquals(400, response.getHttpStatus());
    }

    @Test
    void blankBusinessPartnerIdReturns400() {
      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "  ");
      NeoResponse response = handler.handle(buildContext("GET", params));
      assertEquals(400, response.getHttpStatus());
    }
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void returnsTwoKpiEntries() throws Exception {
      Object[] revenueRow = { new BigDecimal("5000"), new BigDecimal("3000") };
      Object[] expenseRow = { new BigDecimal("2000"), new BigDecimal("1500") };
      when(nativeQuery.list())
          .thenReturn(Collections.singletonList(revenueRow))
          .thenReturn(Collections.singletonList(expenseRow));

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");

      NeoResponse response = handler.handle(buildContext("GET", params));
      assertEquals(200, response.getHttpStatus());

      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
    }

    @Test
    void revenueKpiHasExpectedFields() throws Exception {
      Object[] row = { new BigDecimal("1000"), new BigDecimal("800") };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");

      NeoResponse response = handler.handle(buildContext("GET", params));
      JSONObject kpi = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertTrue(kpi.has("key"));
      assertTrue(kpi.has("label"));
      assertTrue(kpi.has("value"));
      assertTrue(kpi.has("previousValue"));
      assertTrue(kpi.has("trend"));
      assertTrue(kpi.has("format"));
      assertTrue(kpi.has("icon"));
    }

    @Test
    void trendIsCorrectlyCalculated() throws Exception {
      // current=1000, previous=500 => trend = (1000-500)*100/500 = 100.0
      Object[] row = { new BigDecimal("1000"), new BigDecimal("500") };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");

      NeoResponse response = handler.handle(buildContext("GET", params));
      JSONObject kpi = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertEquals(100.0, kpi.getDouble("trend"), 0.1);
    }

    @Test
    void zeroPreviousReturnsTrendZero() throws Exception {
      Object[] row = { new BigDecimal("1000"), BigDecimal.ZERO };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");

      NeoResponse response = handler.handle(buildContext("GET", params));
      JSONObject kpi = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertEquals(0.0, kpi.getDouble("trend"), 0.001);
    }

    @Test
    void emptyQueryResultReturnsZeros() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");

      NeoResponse response = handler.handle(buildContext("GET", params));
      assertEquals(200, response.getHttpStatus());

      JSONObject kpi = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(0.0, kpi.getDouble("value"), 0.001);
    }
  }
}
