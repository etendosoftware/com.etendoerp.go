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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.service.db.CallProcess;

/**
 * Unit tests for {@link PeriodControlDocOpenCloseHandler}.
 *
 * <p>Covers the null-guard branches in {@link PeriodControlDocOpenCloseHandler#doHandle}
 * (PeriodControl not found → 404, Process 168 not found → 500), the happy path (200),
 * and the {@link PeriodControlDocOpenCloseHandler#onError} envelope.
 */
public class PeriodControlDocOpenCloseHandlerTest {

  private static final String RECORD_ID = "pctrl-id-1";

  private NeoContext buildContext() throws Exception {
    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    return NeoContext.builder()
        .specName("open-close-period-control").entityName("documents")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("openClose").recordId(RECORD_ID).requestBody(body).build();
  }

  @Test
  public void testDoHandlePeriodControlNotFoundReturns404() throws Exception {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(PeriodControl.class), anyString())).thenReturn(null);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(404, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("not found"));
    }
  }

  @Test
  public void testDoHandleProcess168NotFoundReturns500() throws Exception {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PeriodControl periodControl = mock(PeriodControl.class);
      when(dal.get(eq(PeriodControl.class), anyString())).thenReturn(periodControl);
      when(dal.get(eq(Process.class), eq("168"))).thenReturn(null);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(500, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("168"));
    }
  }

  @Test
  public void testDoHandleHappyPathReturns200() throws Exception {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<CallProcess> callProcessMock = Mockito.mockStatic(CallProcess.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      doNothing().when(session).refresh(any());

      PeriodControl periodControl = mock(PeriodControl.class);
      when(dal.get(eq(PeriodControl.class), anyString())).thenReturn(periodControl);

      Process process168 = mock(Process.class);
      when(process168.getName()).thenReturn("C_PeriodControl_Process");
      when(dal.get(eq(Process.class), eq("168"))).thenReturn(process168);

      CallProcess cp = mock(CallProcess.class);
      callProcessMock.when(CallProcess::getInstance).thenReturn(cp);
      ProcessInstance pInstance = mock(ProcessInstance.class);
      when(pInstance.getResult()).thenReturn(1L);
      when(pInstance.getErrorMsg()).thenReturn(null);
      when(cp.call(eq(process168), anyString(), isNull())).thenReturn(pInstance);

      NeoResponse r = handler.handle(buildContext());

      assertEquals(200, r.getHttpStatus());
      assertEquals("success", r.getBody().getString("status"));
    }
  }

  @Test
  public void testOnErrorReturns500() throws Exception {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    NeoResponse r = handler.onError("pctrl-id", new RuntimeException("boom"));
    assertEquals(500, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("boom"));
  }

  // ── afterHandle — ETP-4948 Issue 3: accounting-relevant document-category filter ──────

  private NeoContext buildGetContext() {
    return NeoContext.builder()
        .specName("open-close-period-control").entityName("documents")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
  }

  private JSONObject rowWithCategory(String category) throws Exception {
    return new JSONObject().put("id", "pc-" + category).put("documentCategory", category);
  }

  @SuppressWarnings("unchecked")
  private MockedStatic<OBDal> mockAccountedTableIds(Object... tableIds) {
    MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Session session = mock(Session.class);
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.list()).thenReturn(Arrays.asList(tableIds));
    return obDalMock;
  }

  @Test
  public void afterHandleReturnsNullForNonGetMethod() {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    NeoContext ctx = NeoContext.builder()
        .specName("open-close-period-control").entityName("documents")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * ETP-4948 review finding W2: {@code afterHandle} must guard on {@code NeoEndpointType.CRUD}
   * as well as the HTTP method, matching the documented {@code FinancialAccountHandler}
   * convention — a GET on a non-CRUD endpoint type (e.g. a SELECTOR) must not be treated as the
   * plain list/getById fetch this override filters.
   */
  @Test
  public void afterHandleReturnsNullForGetWithNonCrudEndpointType() {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    NeoContext ctx = NeoContext.builder()
        .specName("open-close-period-control").entityName("documents")
        .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR).build();

    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullWhenNoPreviousResult() {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    NeoContext ctx = buildGetContext();
    // getPreviousResult() is never set on this context.

    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullWhenResponseHasNoRows() throws Exception {
    PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
    NeoContext ctx = buildGetContext();
    JSONObject data = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    ctx.setPreviousResult(NeoResponse.ok(data));

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Reproduces Issue 3's own reported bug: SOO (Sales Order) never posts to accounting at all,
   * so it must be dropped even though its row is a real, active {@code C_PeriodControl} entry.
   */
  @Test
  public void afterHandleDropsNonPostableOrderCategory() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockAccountedTableIds("318")) { // only C_Invoice accounted
      PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
      NeoContext ctx = buildGetContext();
      JSONArray rows = new JSONArray().put(rowWithCategory("SOO")).put(rowWithCategory("ARI"));
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", rows));
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertEquals(200, result.getHttpStatus());
      JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, filtered.length());
      assertEquals("ARI", filtered.getJSONObject(0).getString("documentCategory"));
    }
  }

  /**
   * ETP-4948 Issue 3 scope decision: the 5 ETP-4452 globally-excluded document types must also
   * be hidden here, in Calendar's own DocBaseType code space (MMP/DDB/LDC/LCC/CAD), even when
   * their table is actively configured for accounting — no divergence from Not Posted Documents.
   */
  @Test
  public void afterHandleDropsAllFiveEtp4452EquivalentCategoriesEvenWhenAccounted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockAccountedTableIds(
        "325",                                // M_Production
        "30721072789F410E9606D2235CB2A226",  // FIN_Doubtful_Debt
        "082F967CDF7245EB9A150941F326C45C",  // M_LandedCost
        "55A984C314FD4C4FB5E7C32DE36BB07B",  // M_LC_Cost
        "D022B92163074E5E82449C8E0B5AFDF6")) { // M_CostAdjustment
      PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
      NeoContext ctx = buildGetContext();
      JSONArray rows = new JSONArray()
          .put(rowWithCategory("MMP")).put(rowWithCategory("DDB")).put(rowWithCategory("LDC"))
          .put(rowWithCategory("LCC")).put(rowWithCategory("CAD"));
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", rows));
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertEquals(200, result.getHttpStatus());
      JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(0, filtered.length());
    }
  }

  @Test
  public void afterHandleKeepsAccountingRelevantCategoriesAndDropsUnmappedOnes() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockAccountedTableIds("318", "224")) { // C_Invoice, GL_Journal
      PeriodControlDocOpenCloseHandler handler = new PeriodControlDocOpenCloseHandler();
      NeoContext ctx = buildGetContext();
      JSONArray rows = new JSONArray()
          .put(rowWithCategory("ARI"))   // C_Invoice, accounted → kept
          .put(rowWithCategory("GLJ"))   // GL_Journal, accounted → kept
          .put(rowWithCategory("PJI"));  // no known table → dropped
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", rows));
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertEquals(200, result.getHttpStatus());
      JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, filtered.length());
      assertEquals("ARI", filtered.getJSONObject(0).getString("documentCategory"));
      assertEquals("GLJ", filtered.getJSONObject(1).getString("documentCategory"));
    }
  }
}
