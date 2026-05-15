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
 * Unit tests for {@link WidgetRecentInvoicesHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetRecentInvoicesHandlerTest {

  private WidgetRecentInvoicesHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetRecentInvoicesHandler();
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
        .specName("dashboard").entityName("recent-invoices")
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
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void emptyResultReturns200() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, data.length());
    }

    @Test
    void singleRowMapsFieldsCorrectly() throws Exception {
      Object[] row = { "inv-id-1", "Acme Corp", "15-01-2026",
          new BigDecimal("2500.00"), "CO", "INV-001" };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("inv-id-1", item.getString("id"));
      assertEquals("Acme Corp", item.getString("client"));
      assertEquals("15-01-2026", item.getString("date"));
      assertEquals(2500.00, item.getDouble("amount"), 0.01);
      assertEquals("CO", item.getString("status"));
      assertEquals("INV-001", item.getString("documentNo"));
    }

    @Test
    void multipleRowsReturnCorrectCount() throws Exception {
      Object[] row1 = { "id1", "C1", "01-01-2026", new BigDecimal("100"), "CO", "INV-1" };
      Object[] row2 = { "id2", "C2", "02-01-2026", new BigDecimal("200"), "CL", "INV-2" };
      when(nativeQuery.list()).thenReturn(Arrays.asList(row1, row2));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject respData = response.getBody().getJSONObject("response");
      assertEquals(2, respData.getInt("count"));
    }

    @Test
    void nullDocumentNoReturnEmptyString() throws Exception {
      Object[] row = { "id1", "Client", "01-01-2026", new BigDecimal("100"), "CO", null };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("", item.getString("documentNo"));
    }
  }
}
