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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * Unit tests for {@link WidgetActivityHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetActivityHandlerTest {

  private WidgetActivityHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetActivityHandler();
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
        .specName("dashboard").entityName("activity")
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
    void rejectsPut() {
      assertEquals(405, handler.handle(buildContext("PUT")).getHttpStatus());
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
    void emptyResultReturns200WithEmptyData() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, data.length());
    }

    @Test
    void singleInvoiceRowMapsCorrectly() throws Exception {
      Timestamp ts = Timestamp.valueOf("2026-01-15 10:30:00");
      Object[] row = { "invoice", "INV-001", "CO", new BigDecimal("1500.00"),
          ts, "John Doe", "Y", "rec-id-1" };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("rec-id-1", item.getString("id"));
      assertEquals("John Doe", item.getString("author"));
      assertEquals("system", item.getString("type"));
      assertTrue(item.getString("text").contains("INV-001"));
      assertTrue(item.getString("text").contains("completed"));
    }

    @Test
    void draftOrderRowHasNoteType() throws Exception {
      Timestamp ts = Timestamp.valueOf("2026-02-01 08:00:00");
      Object[] row = { "order", "ORD-002", "DR", new BigDecimal("200.00"),
          ts, "Jane", "Y", "rec-id-2" };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("note", item.getString("type"));
      assertTrue(item.getString("text").contains("created"));
    }

    @Test
    void shipmentRowWithNullAmountOmitsDollarSign() throws Exception {
      Timestamp ts = Timestamp.valueOf("2026-03-01 12:00:00");
      Object[] row = { "shipment", "SHP-003", "CO", null, ts, "Admin", "Y", "rec-id-3" };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      String text = item.getString("text");
      assertTrue(text.contains("Shipment"));
      assertTrue(!text.contains("$") || text.contains("—"));
    }

    @Test
    void multipleRowsReturnCorrectCount() throws Exception {
      Timestamp ts = Timestamp.valueOf("2026-01-01 00:00:00");
      Object[] row1 = { "invoice", "INV-1", "CO", new BigDecimal("100"), ts, "U1", "Y", "r1" };
      Object[] row2 = { "order", "ORD-1", "DR", new BigDecimal("200"), ts, "U2", "N", "r2" };
      when(nativeQuery.list()).thenReturn(Arrays.asList(row1, row2));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject respData = response.getBody().getJSONObject("response");
      assertEquals(2, respData.getInt("count"));
      assertEquals(2, respData.getJSONArray("data").length());
    }

    @Test
    void purchaseInvoiceDescriptionContainsPurchase() throws Exception {
      Timestamp ts = Timestamp.valueOf("2026-04-01 09:00:00");
      Object[] row = { "invoice", "PI-100", "CO", new BigDecimal("999"), ts, "Buyer", "N", "r4" };
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

      NeoResponse response = handler.handle(buildContext("GET"));
      JSONObject item = response.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(item.getString("text").toLowerCase().contains("purchase"));
    }
  }
}