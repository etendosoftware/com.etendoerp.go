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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
 *
 * @covers com.etendoerp.go.schemaforge.ContactsBpStatsHandler
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
    @ParameterizedTest(name = "months={0}")
    @NullSource
    @ValueSource(strings = { "12", "abc" })
    void withoutAValidMonthsKeepsTheOriginalTwoEntryResponse(String months) throws Exception {
      Object[] revenueRow = { new BigDecimal("5000"), new BigDecimal("3000") };
      Object[] expenseRow = { new BigDecimal("2000"), new BigDecimal("1500") };
      List<Object[]> revenueRows = Collections.singletonList(revenueRow);
      List<Object[]> expenseRows = Collections.singletonList(expenseRow);
      when(nativeQuery.list()).thenReturn(revenueRows).thenReturn(expenseRows);

      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");
      params.put("months", months);

      NeoResponse response = handler.handle(buildContext("GET", params));
      assertEquals(200, response.getHttpStatus());

      JSONObject body = response.getBody().getJSONObject("response");
      assertEquals(2, body.getJSONArray("data").length());
      assertEquals(2, body.getInt("count"));
      assertFalse(body.has("months"));
      verify(nativeQuery, never()).setParameter(eq("months"), any());

      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(session, times(2)).createNativeQuery(sql.capture());
      for (String captured : sql.getAllValues()) {
        assertFalse(captured.contains(":months"), "original month-vs-month SQL expected");
        assertTrue(captured.contains("i.totallines"), "amounts must be net (totallines)");
        assertFalse(captured.contains("grandtotal"), "amounts must not include VAT");
      }
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

  @Nested
  @DisplayName("months parameter")
  class MonthsParameter {

    @ParameterizedTest(name = "[{0}] is ignored")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "12", "1", "0", "-3", "abc", "3.0" })
    void resolveMonthsIgnoresValuesOutsideTheAllowList(String raw) {
      Map<String, String> params = new HashMap<>();
      params.put("months", raw);
      assertNull(ContactsBpStatsHandler.resolveMonths(params));
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource({ "3, 3", "6, 6", "' 3 ', 3", "' 6', 6" })
    void resolveMonthsAcceptsAllowedValuesTrimmed(String raw, int expected) {
      Map<String, String> params = new HashMap<>();
      params.put("months", raw);
      assertEquals(Integer.valueOf(expected), ContactsBpStatsHandler.resolveMonths(params));
    }

    @ParameterizedTest(name = "months={0}")
    @ValueSource(ints = { 3, 6 })
    void validMonthsAddsPeriodEntriesComparedWithThePrecedingWindow(int months)
        throws Exception {
      Object[] revenueRow = { new BigDecimal("1000"), new BigDecimal("500"),
          new BigDecimal("3000"), new BigDecimal("2000") };
      Object[] expenseRow = { new BigDecimal("200"), new BigDecimal("100"),
          new BigDecimal("900"), BigDecimal.ZERO };
      List<Object[]> revenueRows = Collections.singletonList(revenueRow);
      List<Object[]> expenseRows = Collections.singletonList(expenseRow);
      when(nativeQuery.list()).thenReturn(revenueRows).thenReturn(expenseRows);

      NeoResponse response = handler.handle(buildContext("GET", periodParams(months)));
      assertEquals(200, response.getHttpStatus());

      JSONObject body = response.getBody().getJSONObject("response");
      JSONArray data = body.getJSONArray("data");
      assertEquals(4, data.length());
      assertEquals(4, body.getInt("count"));
      assertEquals(months, body.getInt("months"));

      // The month-vs-month entries keep reading columns [0]/[1].
      assertEquals("revenueThisMonth", data.getJSONObject(0).getString("key"));
      assertEquals(1000.0, data.getJSONObject(0).getDouble("value"), 0.001);
      assertEquals(500.0, data.getJSONObject(0).getDouble("previousValue"), 0.001);

      JSONObject revenue = data.getJSONObject(2);
      assertEquals("revenue", revenue.getString("key"));
      assertEquals(3000.0, revenue.getDouble("value"), 0.001);
      assertEquals(2000.0, revenue.getDouble("previousValue"), 0.001);
      assertEquals(50.0, revenue.getDouble("trend"), 0.001);
      assertTrue(revenue.getBoolean("hasPrevious"));

      JSONObject expenses = data.getJSONObject(3);
      assertEquals("expenses", expenses.getString("key"));
      assertEquals(900.0, expenses.getDouble("value"), 0.001);
      assertEquals(0.0, expenses.getDouble("previousValue"), 0.001);
      assertEquals(0.0, expenses.getDouble("trend"), 0.001);
      assertFalse(expenses.getBoolean("hasPrevious"));

      verify(nativeQuery, times(2)).setParameter(eq("months"), eq(Integer.valueOf(months)));

      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(session, times(2)).createNativeQuery(sql.capture());
      for (String captured : sql.getAllValues()) {
        assertTrue(captured.contains(":months"), "period SQL expected");
        assertTrue(captured.contains("i.totallines"), "amounts must be net (totallines)");
        assertFalse(captured.contains("grandtotal"), "amounts must not include VAT");
      }
    }

    @Test
    void negativePreviousPeriodCountsAsPreviousAndTrendUsesItsAbsoluteValue()
        throws Exception {
      Object[] revenueRow = { BigDecimal.ZERO, BigDecimal.ZERO,
          new BigDecimal("100"), new BigDecimal("-200") };
      Object[] shortExpenseRow = { new BigDecimal("5") };
      List<Object[]> revenueRows = Collections.singletonList(revenueRow);
      List<Object[]> expenseRows = Collections.singletonList(shortExpenseRow);
      when(nativeQuery.list()).thenReturn(revenueRows).thenReturn(expenseRows);

      NeoResponse response = handler.handle(buildContext("GET", periodParams(6)));
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

      JSONObject revenue = data.getJSONObject(2);
      assertEquals(-200.0, revenue.getDouble("previousValue"), 0.001);
      assertEquals(150.0, revenue.getDouble("trend"), 0.001);
      assertTrue(revenue.getBoolean("hasPrevious"));

      // A row shorter than the four period columns pads the missing ones with zero.
      assertEquals(5.0, data.getJSONObject(1).getDouble("value"), 0.001);
      assertEquals(0.0, data.getJSONObject(1).getDouble("previousValue"), 0.001);
      JSONObject expenses = data.getJSONObject(3);
      assertEquals(0.0, expenses.getDouble("value"), 0.001);
      assertEquals(0.0, expenses.getDouble("previousValue"), 0.001);
      assertFalse(expenses.getBoolean("hasPrevious"));
    }

    private Map<String, String> periodParams(int months) {
      Map<String, String> params = new HashMap<>();
      params.put("businessPartnerId", "bp-id-1");
      params.put("months", String.valueOf(months));
      return params;
    }
  }
}
