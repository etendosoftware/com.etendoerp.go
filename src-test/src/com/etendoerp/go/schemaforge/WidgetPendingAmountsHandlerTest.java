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
 * Unit tests for {@link WidgetPendingAmountsHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetPendingAmountsHandlerTest {

  private WidgetPendingAmountsHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetPendingAmountsHandler();
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
        .specName("dashboard").entityName("pending-amounts")
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
    void rejectsPatch() {
      assertEquals(405, handler.handle(buildContext("PATCH")).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void returnsToCollectAndToPay() throws Exception {
      Object[] salesRow = { 5L, new BigDecimal("10000.00") };
      Object[] purchaseRow = { 3L, new BigDecimal("4500.50") };
      when(nativeQuery.uniqueResult())
          .thenReturn(salesRow)
          .thenReturn(purchaseRow);

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.has("toCollect"));
      assertTrue(data.has("toPay"));

      JSONObject toCollect = data.getJSONObject("toCollect");
      assertEquals(5, toCollect.getLong("count"));
      assertEquals(10000.00, toCollect.getDouble("amount"), 0.01);

      JSONObject toPay = data.getJSONObject("toPay");
      assertEquals(3, toPay.getLong("count"));
      assertEquals(4500.50, toPay.getDouble("amount"), 0.01);
    }

    @Test
    void zeroAmountsReturnZero() throws Exception {
      Object[] emptyRow = { 0L, new BigDecimal("0") };
      when(nativeQuery.uniqueResult()).thenReturn(emptyRow);

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONObject("toCollect").getLong("count"));
      assertEquals(0.0, data.getJSONObject("toCollect").getDouble("amount"), 0.001);
    }
  }
}
