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
import java.util.Arrays;
import java.util.Collections;

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
 * Unit tests for {@link WidgetRevenueTrendHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetRevenueTrendHandlerTest {

  private WidgetRevenueTrendHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetRevenueTrendHandler();
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

  private NeoContext buildContext(String method) {
    return NeoContext.builder()
        .specName("dashboard").entityName("revenue-trend")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .build();
  }

  @Nested
  @DisplayName("Method guard")
  class MethodGuard {
    @Test
    void rejectsPost() {
      assertEquals(405, handler.handle(buildContext("POST")).getHttpStatus());
    }

    @Test
    void rejectsDelete() {
      assertEquals(405, handler.handle(buildContext("DELETE")).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void emptyResultReturns200WithEmptyArrays() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject trend = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(0, trend.getJSONArray("labels").length());
      assertEquals(0, trend.getJSONArray("values").length());
      assertEquals(0, trend.getJSONArray("expenseValues").length());
    }

    @Test
    void twoMonthsMappedCorrectly() throws Exception {
      Object[] jan = { "Jan", new BigDecimal("5000"), new BigDecimal("2000") };
      Object[] feb = { "Feb", new BigDecimal("7000"), new BigDecimal("3000") };
      when(nativeQuery.list()).thenReturn(Arrays.asList(jan, feb));

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject trend = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      JSONArray labels = trend.getJSONArray("labels");
      JSONArray values = trend.getJSONArray("values");
      JSONArray expenseValues = trend.getJSONArray("expenseValues");

      assertEquals(2, labels.length());
      assertEquals("Jan", labels.getString(0));
      assertEquals("Feb", labels.getString(1));
      assertEquals(5000L, values.getLong(0));
      assertEquals(7000L, values.getLong(1));
      assertEquals(2000L, expenseValues.getLong(0));
      assertEquals(3000L, expenseValues.getLong(1));
    }

    @Test
    void labelTrimsWhitespace() throws Exception {
      Object[] row = { "  Mar  ", new BigDecimal("100"), new BigDecimal("50") };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject trend = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Mar", trend.getJSONArray("labels").getString(0));
    }
  }
}
