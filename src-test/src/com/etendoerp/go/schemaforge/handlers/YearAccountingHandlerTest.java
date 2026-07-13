/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link YearAccountingHandler}.
 *
 * <p>Pure Mockito style, matching {@link
 * com.etendoerp.go.schemaforge.handlers.ChartOfAccountsHandlerTest}'s own convention in this
 * package (which explicitly excludes OBDal-querying methods from unit coverage and treats them as
 * "integration-test territory") and {@link com.etendoerp.go.schemaforge.PeriodOpenCloseHandlerTest}
 * (no {@code OBBaseTest}, no real DB). The {@code Session.createQuery(...).setParameter(...).list()}
 * chain is mocked (a supported convention already used elsewhere in this test suite, e.g. {@code
 * SelectorAuxResolverTest}), so the real HQL never executes against the dev DB — this handler is
 * read-only with no persistence side effects, unlike Tasks 7/8's legacy-servlet concerns, but the
 * same principle of not depending on a live DB connection for a unit test still applies.
 */
public class YearAccountingHandlerTest {

  private NeoContext buildListContext(String yearId) {
    Map<String, String> queryParams = new HashMap<>();
    if (yearId != null) {
      queryParams.put("year", yearId);
    }
    return NeoContext.builder()
        .specName("calendar").entityName("accounting")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .queryParams(queryParams).build();
  }

  @Test
  public void nonCrudEndpointFallsThrough() {
    YearAccountingHandler handler = new YearAccountingHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("accounting")
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION).build();

    assertNull(handler.handle(context));
  }

  @Test
  public void nonGetMethodFallsThrough() {
    YearAccountingHandler handler = new YearAccountingHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("accounting")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();

    assertNull(handler.handle(context));
  }

  @Test
  public void singleRecordGetFallsThrough() {
    YearAccountingHandler handler = new YearAccountingHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("accounting")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId("some-id").build();

    assertNull(handler.handle(context));
  }

  @Test
  public void missingYearQueryParamReturns400() {
    YearAccountingHandler handler = new YearAccountingHandler();

    NeoResponse r = handler.handle(buildListContext(null));

    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void blankYearQueryParamReturns400() {
    YearAccountingHandler handler = new YearAccountingHandler();

    NeoResponse r = handler.handle(buildListContext("  "));

    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void listReturnsAggregatedRowsScopedToYear() throws Exception {
    YearAccountingHandler handler = new YearAccountingHandler();

    Object[] row1 = { "fact-1", "20000000", "R", new BigDecimal("100.00"), BigDecimal.ZERO, "Year close" };
    Object[] row2 = { "fact-2", "43000000", "C", null, new BigDecimal("50.00"), null };
    List<Object[]> rows = Arrays.asList(row1, row2);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      Query<Object[]> query = mock(Query.class);
      when(session.createQuery(anyString())).thenReturn(query);
      when(query.setParameter(eq("yearId"), anyString())).thenReturn(query);
      when(query.list()).thenReturn(rows);

      NeoResponse r = handler.handle(buildListContext("year-1"));

      assertEquals(200, r.getHttpStatus());
      org.codehaus.jettison.json.JSONArray data = r.getBody().getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("20000000", data.getJSONObject(0).getString("account"));
      assertEquals("100.00", data.getJSONObject(0).getString("debit"));
      assertEquals("0", data.getJSONObject(0).getString("credit"));
      assertEquals("Year close", data.getJSONObject(0).getString("description"));
      // null description/debit are defensively coerced, not left as JSON null
      assertEquals("", data.getJSONObject(1).getString("description"));
      assertEquals("0", data.getJSONObject(1).getString("debit"));
      assertEquals("50.00", data.getJSONObject(1).getString("credit"));
    }
  }

  @Test
  public void emptyResultSetReturns200WithEmptyArray() throws Exception {
    YearAccountingHandler handler = new YearAccountingHandler();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      Query<Object[]> query = mock(Query.class);
      when(session.createQuery(anyString())).thenReturn(query);
      when(query.setParameter(eq("yearId"), anyString())).thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      NeoResponse r = handler.handle(buildListContext("year-with-no-entries"));

      assertEquals(200, r.getHttpStatus());
      assertEquals(0, r.getBody().getJSONArray("data").length());
    }
  }

  @Test
  public void queryFailureReturns500() throws Exception {
    YearAccountingHandler handler = new YearAccountingHandler();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString())).thenThrow(new RuntimeException("boom"));

      NeoResponse r = handler.handle(buildListContext("year-1"));

      assertEquals(500, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("boom"));
    }
  }
}
