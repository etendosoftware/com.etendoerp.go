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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.validation.ValidationException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.erpCommon.ad_process.ConvertQuotationIntoOrder;
import org.openbravo.model.common.order.Order;

/**
 * Unit tests for SalesQuotationHeaderHandler.handle() and afterHandle().
 *
 * <p>Verifies that both total-discount interception paths are wired correctly:
 * <ul>
 *   <li>applyTotalDiscountBeforeComplete for documentAction=CO (CRUD PATCH path)</li>
 *   <li>syncTotalDiscountOnDocAction for DocAction process-button (SendToEvaluationModal DR→UE path)</li>
 * </ul>
 *
 * <p>ETP-4027: the {@code Convertquotation} action is intercepted in {@code handle()} and
 * delegates to {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
 * {@code recalculatePrices=false} so that quotation prices are preserved in the new order.
 *
 * <p>{@code testIsRejectDocAction_table} also guards {@link DocActionRequestSupport}, the
 * DocAction value parsing {@code isRejectDocAction} delegates to.
 *
 * <p>ETP-5840: {@code Convertquotation} is refused for a foreign quotation without a rate, and the
 * created order's rate is recomputed from its own date instead of copied.
 *
 * <p>ETP-5704: {@code DocAction} {@code RE} is refused unless the quotation is voided, through
 * {@link DocActionRefusalGuard}; the DocAction cases resolve the effective action against a mocked
 * DAL, so they reach the sync and the router for the right reason.
 *
 * @covers com.etendoerp.go.schemaforge.SalesQuotationHeaderHandler
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 * @covers com.etendoerp.go.schemaforge.DocActionRequestSupport
 * @covers com.etendoerp.go.schemaforge.DocActionRefusalGuard
 * @covers com.etendoerp.go.schemaforge.QuotationShipmentLinks
 */
public class SalesQuotationHeaderHandlerTest {

  private static final String QUOTATION_ID = "quotation-abc-123";

  /**
   * ETP-6007: without a DAL, {@code OBContext.setAdminMode(true)} pushes onto its thread-local
   * stack and only then throws (no session factory). {@code DocumentCurrencyRate} and the
   * {@code handleConvertQuotation} path call it OUTSIDE their {@code try}, so the {@code finally}
   * that would restore the frame is never entered, and both then swallow or log the exception
   * ("[ETP-5840] Could not evaluate the exchange rate of Order …", "[ETP-4027]
   * handleConvertQuotation failed …"). The cases pass and the orphan frames are charged to the next
   * {@code OBBaseTest} class in the same Gradle worker running as a non-admin user —
   * {@code StorageDetailCostColumnsIntegrationTest} in the merge-block cut. Same symptom and same
   * remedy as ETP-5953 ({@code NeoDefaultsServiceTest}, {@code NeoProcessServiceTest}).
   *
   * <p>The root fix is to make those sites enter through {@code NeoAdminMode#enter}, which already
   * restores the frame when {@code setAdminMode} throws; that is product code, so it goes in its
   * own ticket. It is a test-environment leak only: in production an {@code OBContext} always
   * exists, so {@code setAdminContextLocally()} is never reached and the pairs stay balanced.
   */
  @After
  public void clearAdminModeLeakedWithoutDal() {
    OBContext.clearAdminModeStack();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static SalesQuotationHeaderHandler handlerWith(
      TotalDiscountService svc, NeoCloneRecordHandler clone) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    setField(handler, "cloneRecordHandler", clone);
    return handler;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static JSONObject bodyWith(String key, String value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ── applyTotalDiscountBeforeComplete wiring ───────────────────────────────

  /**
   * CRUD PATCH with documentAction=CO fires recalculate via applyTotalDiscountBeforeComplete.
   */
  @Test
  public void testHandle_crudPatchCO_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc).recalculate(QUOTATION_ID, false);
  }

  /**
   * CRUD PATCH with documentAction=WP does not fire recalculate.
   */
  @Test
  public void testHandle_crudPatchNotCO_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "WP"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── syncTotalDiscountOnDocAction wiring ───────────────────────────────────

  /**
   * ACTION DocAction fires recalculate via syncTotalDiscountOnDocAction — the SendToEvaluationModal
   * DR→UE path: {@code fieldValues: {}} on a draft whose stored DocAction is {@code CO}. The
   * Reactivate refusal (ETP-5704) resolves that action, finds CO and lets the request through to
   * the sync and the router.
   */
  @Test
  public void testHandle_actionDocAction_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    Tab tab = mock(Tab.class);
    OBDal dal = quotationDal(tab, "CO", "DR");
    NeoContext ctx = docActionCtx("DocAction", "{\"fieldValues\":{}}", tab);

    NeoResponse result = handleWithDal(handlerWith(svc, clone), ctx, dal);

    assertNull(result);
    verify(svc).recalculate(QUOTATION_ID, false);
    verify(clone).handle(ctx);
  }

  /**
   * ACTION documentAction with empty body → neither path fires recalculate: the body asks for no
   * completion and, outside the MCP, the field-name spelling is not the sync's button name. The
   * stored CO resolves, so the request reaches the router instead of failing closed.
   */
  @Test
  public void testHandle_actionDocumentActionNoBody_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    Tab tab = mock(Tab.class);
    OBDal dal = quotationDal(tab, "CO", "DR");
    NeoContext ctx = docActionCtx("documentAction", "{}", tab);

    NeoResponse result = handleWithDal(handlerWith(svc, clone), ctx, dal);

    assertNull(result);
    verify(clone).handle(ctx);
    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── Reactivate (DocAction RE) refused unless the quotation is voided ──────

  private static final String TAB_ID = "tab-quotation";
  /** Stored-DocAction sentinel: the context carries no tab, so the action cannot be resolved. */
  private static final String NO_TAB = "<no tab>";
  /** Stored-DocAction sentinel: the tab resolves, no quotation has this id. */
  private static final String RECORD_NOT_FOUND = "<record not found>";
  /** Status sentinel: the DocAction resolves, but the quotation cannot be loaded for its status. */
  private static final String STATUS_UNREADABLE = "<status unreadable>";
  /** The DocAction list the stubbed property accepts, exact case, as the AD list does. */
  private static final Set<String> DOC_ACTION_LIST = Set.of("CO", "RE", "RJ", "CL", "VO", "--");

  /** A POST of the DocAction button (named {@code fieldName}) on the quotation, through {@code tab}. */
  private static NeoContext docActionCtx(String fieldName, String body, Tab tab) throws Exception {
    return NeoContext.builder()
        .specName("sales-quotation").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName).recordId(QUOTATION_ID).adTab(tab)
        .requestBody(new JSONObject(body))
        .build();
  }

  /**
   * A DAL that resolves the quotation the way {@code DocActionResolution} and
   * {@code SalesQuotationHeaderHandler.isNotVoided} read it: {@code tab} → its table → the
   * quotation, whose stored DocAction is {@code stored} and whose status is {@code status}. A root
   * {@code docAction} is written only when it is in {@link #DOC_ACTION_LIST}. Configures
   * {@code tab}; call it on its own line, never inside a {@code thenReturn}.
   */
  private static OBDal quotationDal(Tab tab, String stored, String status) {
    OBDal dal = mock(OBDal.class);
    Table table = mock(Table.class);
    Property documentAction = mock(Property.class);
    Entity entity = mock(Entity.class);
    Order quotation = mock(Order.class);
    when(tab.getId()).thenReturn(TAB_ID);
    when(tab.getTable()).thenReturn(table);
    when(table.getName()).thenReturn("Order");
    doAnswer(invocation -> {
      if (!DOC_ACTION_LIST.contains(invocation.getArgument(0))) {
        throw new ValidationException();
      }
      return null;
    }).when(documentAction).checkIsValidValue(any());
    when(entity.hasProperty("documentAction")).thenReturn(true);
    when(entity.getProperty("documentAction")).thenReturn(documentAction);
    when(quotation.getEntity()).thenReturn(entity);
    when(quotation.get("documentAction")).thenReturn(stored);
    when(quotation.getDocumentStatus()).thenReturn(status);
    when(dal.get(Tab.class, TAB_ID)).thenReturn(tab);
    if (!RECORD_NOT_FOUND.equals(stored)) {
      when(dal.get("Order", QUOTATION_ID)).thenReturn(quotation);
    }
    if (!STATUS_UNREADABLE.equals(status)) {
      when(dal.get(Order.class, QUOTATION_ID)).thenReturn(quotation);
    }
    return dal;
  }

  /** Runs {@code handler.handle(ctx)} with {@code dal} as the DAL and admin mode a no-op. */
  private static NeoResponse handleWithDal(SalesQuotationHeaderHandler handler, NeoContext ctx,
      OBDal dal) {
    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      return handler.handle(ctx);
    }
  }

  /**
   * Reactivate guard through {@code handle()}: {@code RE} is refused with 400
   * {@code ETGO_QuotationReactivateNotVoided} unless the stored {@code documentStatus} is
   * {@code VO}, decided on the DocAction that will actually run (root {@code docAction} when the
   * DAL accepts it, else the stored value — never {@code fieldValues}). An unknown id answers 404
   * whatever the action; an action or status that cannot be read fails closed with 500
   * {@code ETGO_DocActionCheckFailed}. A refusal reaches neither the total-discount sync nor the
   * router. {@code RJ} keeps going to the reject flow, also from {@code fieldValues} on a quotation
   * whose stored action is RE.
   */
  @Test
  public void testHandle_reactivateIsRefusedUnlessVoided_table() throws Exception {
    final String reject = "<reject flow>";
    final String routed = "<router>";
    final String notVoided = SalesQuotationHeaderHandler.MSG_REACTIVATE_NOT_VOIDED;
    final String checkFailed = DocActionRefusalGuard.MSG_CHECK_FAILED;
    // Columns: the label, the button name, the body, the stored DocAction, the stored status, the
    // expected status (null when it is not refused) and the expected outcome: the refusal's
    // messageKeys entry (else its error message), or the flow that answered.
    Object[][] cases = {
        { "root RE on a quotation under evaluation is refused",
            "DocAction", "{\"docAction\":\"RE\"}", "CO", "UE", 400, notVoided },
        { "root RE on a voided quotation runs",
            "DocAction", "{\"docAction\":\"RE\"}", "RE", "VO", null, routed },
        { "empty body runs the stored RE of a draft and is refused",
            "DocAction", "{}", "RE", "DR", 400, notVoided },
        { "root RE wins over fieldValues CO",
            "DocAction", "{\"fieldValues\":{\"documentAction\":\"CO\"},\"docAction\":\"RE\"}",
            "CO", "UE", 400, notVoided },
        { "fieldValues RE without root runs the stored CO",
            "DocAction", "{\"fieldValues\":{\"documentAction\":\"RE\"}}", "CO", "UE", null,
            routed },
        { "root re outside the list is not written, the stored RE runs and is refused",
            "DocAction", "{\"docAction\":\"re\"}", "RE", "UE", 400, notVoided },
        { "the button named by its field name is guarded too",
            "documentAction", "{\"docAction\":\"RE\"}", "CO", "UE", 400, notVoided },
        { "an unknown quotation id answers 404 whatever the action",
            "DocAction", "{\"docAction\":\"CO\"}", RECORD_NOT_FOUND, "UE", 404,
            NeoActionRecordGuard.MSG_RECORD_NOT_FOUND },
        { "an action that cannot be resolved fails closed",
            "DocAction", "{\"docAction\":\"RE\"}", NO_TAB, "UE", 500, checkFailed },
        { "a status that cannot be read fails closed",
            "DocAction", "{\"docAction\":\"RE\"}", "RE", STATUS_UNREADABLE, 500, checkFailed },
        { "fieldValues RJ with a stored RE still goes to the reject flow",
            "DocAction", "{\"fieldValues\":{\"documentAction\":\"RJ\"}}", "RE", "UE", null,
            reject },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      String stored = (String) c[3];
      Integer expectedStatus = (Integer) c[5];
      String expected = (String) c[6];

      TotalDiscountService svc = mock(TotalDiscountService.class);
      NeoCloneRecordHandler router = mock(NeoCloneRecordHandler.class);
      RejectQuotationHandler rejectHandler = mock(RejectQuotationHandler.class);
      SalesQuotationHeaderHandler handler = fullyWiredHandler(svc, router,
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class), rejectHandler,
          mock(CreateRejectReasonHandler.class));
      Tab tab = mock(Tab.class);
      OBDal dal = quotationDal(tab, stored, (String) c[4]);
      NeoContext ctx = docActionCtx((String) c[1], (String) c[2],
          NO_TAB.equals(stored) ? null : tab);
      NeoResponse rejected = NeoResponse.ok(new JSONObject().put("documentStatus", "CJ"));
      when(rejectHandler.reject(ctx)).thenReturn(rejected);

      NeoResponse result = handleWithDal(handler, ctx, dal);

      if (reject.equals(expected)) {
        assertSame(label, rejected, result);
        verify(router, never()).handle(any());
      } else if (routed.equals(expected)) {
        assertNull(label, result);
        verify(rejectHandler, never()).reject(any());
        verify(router).handle(ctx);
      } else {
        assertNotNull(label, result);
        assertEquals(label, expectedStatus.intValue(), result.getHttpStatus());
        JSONObject error = result.getBody().getJSONObject("error");
        JSONArray keys = error.optJSONArray(NeoProcessService.MESSAGE_KEYS);
        assertEquals(label, expected,
            keys != null && keys.length() > 0 ? keys.getString(0) : error.getString("message"));
        verify(rejectHandler, never()).reject(any());
        verify(router, never()).handle(any());
        Mockito.verifyNoInteractions(svc);
      }
    }
  }

  // ── clone dispatch ────────────────────────────────────────────────────────

  /**
   * handle() short-circuits when the clone handler responds.
   */
  @Test
  public void testHandle_cloneResponds_shortCircuits() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord")
        .recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handlerWith(svc, clone).handle(ctx));
  }

  /**
   * handle() returns null when no downstream handler matches.
   */
  @Test
  public void testHandle_noMatchingHandler_returnsNull() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(null);

    assertNull(handlerWith(svc, clone).handle(ctx));
  }

  // ── ETP-4027: injected handler dispatch (was previously newed, now @Inject) ─

  /**
   * Helper that builds a fully-wired handler where all six injected delegates
   * are mocks. Each mock returns null by default (Mockito behaviour), which
   * means the dispatch will fall through unless a specific mock is configured
   * to return a response.
   */
  private static SalesQuotationHeaderHandler fullyWiredHandler(
      TotalDiscountService svc,
      NeoCloneRecordHandler clone,
      CurrencyOptionsHandler currencyOptions,
      CreateDraftInvoiceHandler createDraftInvoice,
      RejectQuotationHandler rejectQuotation,
      CreateRejectReasonHandler createRejectReason) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    setField(handler, "cloneRecordHandler", clone);
    setField(handler, "currencyOptionsHandler", currencyOptions);
    setField(handler, "createDraftInvoiceHandler", createDraftInvoice);
    setField(handler, "rejectQuotationHandler", rejectQuotation);
    setField(handler, "createRejectReasonHandler", createRejectReason);
    return handler;
  }

  /**
   * ACTION currencyOptions → dispatches to the injected {@link CurrencyOptionsHandler}.
   *
   * <p>Before ETP-4027 this handler was instantiated with {@code new CurrencyOptionsHandler()},
   * which bypassed CDI. After ETP-4027 it is {@code @Inject}ed. This test verifies the
   * injected instance is the one that receives the call.
   */
  @Test
  public void testHandle_currencyOptionsAction_dispatchesToCurrencyOptionsHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("currencies", "[]"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("currencyOptions").recordId(QUOTATION_ID).build();
    when(currencyOptionsHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(currencyOptionsHandler).handle(ctx);
  }

  /**
   * ACTION createDraftInvoice → dispatches to the injected {@link CreateDraftInvoiceHandler}.
   *
   * <p>Before ETP-4027 this was {@code new CreateDraftInvoiceHandler()}, skipping CDI and
   * leaving its own {@code @Inject} fields null (causing NPE at runtime). After ETP-4027 the
   * handler is injected — this test confirms the injected instance receives the call.
   */
  @Test
  public void testHandle_createDraftInvoiceAction_dispatchesToCreateDraftInvoiceHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.created(new JSONObject().put("id", "inv-1"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createDraftInvoice").recordId(QUOTATION_ID).build();
    when(createDraftInvoiceHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(createDraftInvoiceHandler).handle(ctx);
  }

  /**
   * ACTION rejectQuotation → dispatches to the injected {@link RejectQuotationHandler}.
   *
   * <p>Same CDI-bypass bug as createDraftInvoice — fixed in ETP-4027.
   */
  @Test
  public void testHandle_rejectQuotationAction_dispatchesToRejectQuotationHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("rejected", true));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("rejectQuotation").recordId(QUOTATION_ID).build();
    when(rejectQuotationHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(rejectQuotationHandler).handle(ctx);
  }

  /**
   * ACTION createRejectReason → dispatches to the injected {@link CreateRejectReasonHandler}.
   *
   * <p>Same CDI-bypass bug — fixed in ETP-4027.
   */
  @Test
  public void testHandle_createRejectReasonAction_dispatchesToCreateRejectReasonHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.created(new JSONObject().put("reason", "reason-1"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createRejectReason").recordId(QUOTATION_ID).build();
    when(createRejectReasonHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(createRejectReasonHandler).handle(ctx);
  }

  /**
   * Unknown action field name — all handlers return null, so handle() returns null.
   * Guards against over-eager short-circuiting on any unrecognised action name.
   */
  @Test
  public void testHandle_unknownAction_returnsNull() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").recordId(QUOTATION_ID).build();

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertNull(result);
    // Every handler was consulted — none short-circuited early.
    verify(currencyOptionsHandler).handle(ctx);
    verify(createDraftInvoiceHandler).handle(ctx);
    verify(rejectQuotationHandler).handle(ctx);
    verify(createRejectReasonHandler).handle(ctx);
  }

  // ── ETP-5535: DocAction = RJ is routed to the reject flow ────────────────

  /**
   * Table-driven: which requests count as "run the DocAction button with RJ". The value is read
   * from the three places action requests carry it (root {@code docAction}, root
   * {@code documentAction}, {@code fieldValues.documentAction}) and the button is named by its
   * column or its field; when root {@code docAction} and {@code fieldValues} disagree, root
   * {@code docAction} — the value the process runs — decides. Anything else — another value, the SPA's {@code fieldValues: {}}, a GET,
   * a CRUD request, another action — must stay on the pre-ETP-5535 path.
   */
  @Test
  public void testIsRejectDocAction_table() throws Exception {
    Object[][] cases = {
        { "root docAction RJ", "POST", NeoEndpointType.ACTION, "DocAction",
            "{\"docAction\":\"RJ\"}", true },
        { "root documentAction RJ", "POST", NeoEndpointType.ACTION, "DocAction",
            "{\"documentAction\":\"RJ\"}", true },
        { "fieldValues.documentAction RJ", "POST", NeoEndpointType.ACTION, "documentAction",
            "{\"fieldValues\":{\"documentAction\":\"RJ\"}}", true },
        { "value CO", "POST", NeoEndpointType.ACTION, "DocAction",
            "{\"docAction\":\"CO\"}", false },
        { "SPA empty fieldValues", "POST", NeoEndpointType.ACTION, "DocAction",
            "{\"fieldValues\":{}}", false },
        { "GET", "GET", NeoEndpointType.ACTION, "DocAction",
            "{\"docAction\":\"RJ\"}", false },
        { "non-ACTION endpoint", "POST", NeoEndpointType.CRUD, "DocAction",
            "{\"documentAction\":\"RJ\"}", false },
        { "rejectQuotation action name", "POST", NeoEndpointType.ACTION, "rejectQuotation",
            "{\"docAction\":\"RJ\"}", false },
        { "root docAction CO wins over fieldValues RJ", "POST", NeoEndpointType.ACTION,
            "DocAction", "{\"fieldValues\":{\"documentAction\":\"RJ\"},\"docAction\":\"CO\"}",
            false },
        { "root docAction RJ wins over fieldValues CO", "POST", NeoEndpointType.ACTION,
            "DocAction", "{\"fieldValues\":{\"documentAction\":\"CO\"},\"docAction\":\"RJ\"}",
            true },
    };
    for (Object[] c : cases) {
      NeoContext ctx = NeoContext.builder()
          .httpMethod((String) c[1]).endpointType((NeoEndpointType) c[2])
          .fieldName((String) c[3]).recordId(QUOTATION_ID)
          .requestBody(new JSONObject((String) c[4]))
          .build();
      assertEquals((String) c[0], c[5], SalesQuotationHeaderHandler.isRejectDocAction(ctx));
    }
  }

  /**
   * DocAction with RJ is answered by {@link RejectQuotationHandler#reject} — the flow the UI's
   * Reject button runs — and never reaches the total-discount sync (which recalculates on every
   * DocAction) nor the action router.
   */
  @Test
  public void testHandle_docActionRJ_delegatesToRejectAndSkipsTotalDiscount() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    SalesQuotationHeaderHandler handler = fullyWiredHandler(svc,
        mock(NeoCloneRecordHandler.class), mock(CurrencyOptionsHandler.class),
        mock(CreateDraftInvoiceHandler.class), rejectQuotationHandler,
        mock(CreateRejectReasonHandler.class));

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("documentStatus", "CJ"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("DocAction")
        .recordId(QUOTATION_ID)
        .requestBody(new JSONObject("{\"docAction\":\"RJ\",\"rejectReason\":\"rr-1\"}"))
        .build();
    when(rejectQuotationHandler.reject(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
    verify(rejectQuotationHandler).reject(ctx);
    verify(rejectQuotationHandler, never()).handle(any());
    Mockito.verifyNoInteractions(svc);
  }

  // ── ETP-4027: Convertquotation interception in handle() ──────────────────

  /**
   * When {@code handle()} receives a {@code Convertquotation} ACTION, it must call
   * {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
   * {@code recalculatePrices=false} and return a non-null response, short-circuiting
   * the default NEO button handler.
   *
   * <p>Passing {@code false} ensures the prices agreed in the quotation are kept in the
   * new sales order. The default behaviour ({@code true}) would re-fetch prices from the
   * active price list.
   */
  @Test
  public void testHandle_convertQuotationAction_preservesQuotationPrices() throws Exception {
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    Order order = mock(Order.class);
    when(order.getId()).thenReturn("order-001");
    when(order.getDocumentNo()).thenReturn("SO-001");
    when(process.convertQuotationIntoSalesOrder(eq(false), eq("q-001"))).thenReturn(order);

    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class),
        mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class),
        mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class),
        mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("Convertquotation").recordId("q-001").build();

    NeoResponse result = handler.handle(ctx);

    // Non-null proves handle() short-circuited the default button handler.
    assertNotNull(result);
    // recalculatePrices=false is the critical invariant — quotation prices must be kept.
    verify(process).convertQuotationIntoSalesOrder(false, "q-001");
  }

  /** ETP-5710: an MCP etendo_action call reaches the handler under the button's field name. */
  @Test
  public void testHandle_convertQuotationByFieldName_preservesQuotationPrices() throws Exception {
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    Order order = mock(Order.class);
    when(order.getId()).thenReturn("order-001");
    when(order.getDocumentNo()).thenReturn("SO-001");
    when(process.convertQuotationIntoSalesOrder(eq(false), eq("q-001"))).thenReturn(order);

    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class),
        mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class),
        mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class),
        mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("convertquotation").recordId("q-001").build();

    NeoResponse result = handler.handle(ctx);

    // Non-null proves handle() short-circuited the default button handler.
    assertNotNull(result);
    // recalculatePrices=false is the critical invariant — quotation prices must be kept.
    verify(process).convertQuotationIntoSalesOrder(false, "q-001");
  }

  // ── ETP-5528: the converted order is reactivated to Draft ─────────────────

  /**
   * Runs {@code Convertquotation} on a handler whose conversion returns {@code order}, with
   * {@link OrderDocActionSupport} statically mocked by the caller.
   */
  private static NeoResponse convertQuotation(Order order) throws Exception {
    return convertQuotation(order, (rates, session) -> { });
  }

  /**
   * {@link #convertQuotation(Order)} with the DAL mocked (the quotation itself is not found, so the
   * pre-conversion rate check lets it through) and every {@link DocumentCurrencyRate} built during
   * the conversion mocked; {@code verifier} receives those and the session the order is refreshed
   * on.
   */
  private static NeoResponse convertQuotation(Order order,
      java.util.function.BiConsumer<List<DocumentCurrencyRate>, Session> verifier)
      throws Exception {
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    when(process.convertQuotationIntoSalesOrder(false, "q-5528")).thenReturn(order);
    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class);
         MockedConstruction<DocumentCurrencyRate> rates =
             Mockito.mockConstruction(DocumentCurrencyRate.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-5528").build());
      verifier.accept(rates.constructed(), session);
      return result;
    }
  }

  /** An order whose documentStatus reads {@code first}, then {@code then} on every later call. */
  private static Order orderWithStatus(String first, String then) {
    Order order = mock(Order.class);
    when(order.getId()).thenReturn("order-5528");
    when(order.getDocumentNo()).thenReturn("SO-5528");
    when(order.getDocumentStatus()).thenReturn(first, then);
    return order;
  }

  /**
   * Core completes the order it creates: a CO order is reactivated through C_Order_Post 'RE' and
   * the response reports the Draft status the procedure left.
   */
  @Test
  public void testConvertQuotation_completedOrder_isReactivatedToDraft() throws Exception {
    Order order = orderWithStatus("CO", "DR");
    try (MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class)) {
      docAction.when(() -> OrderDocActionSupport.runDocAction(order, "RE")).thenReturn(true);

      NeoResponse result = convertQuotation(order);

      docAction.verify(() -> OrderDocActionSupport.runDocAction(order, "RE"));
      assertEquals(200, result.getHttpStatus());
      assertEquals("order-5528", result.getBody().getString("salesOrderId"));
      assertEquals("DR", result.getBody().getString("documentStatus"));
    }
  }

  /**
   * ETP-5051 — the order created from the quotation and its lines are checked against the plan
   * quotas after the conversion and its reactivation, before the answer. Nothing has been committed
   * yet, so a refusal rolls the WHOLE conversion back: 403 {@code QUOTA_EXCEEDED}, a rollback, no
   * commit, and no {@code salesOrderId} handed to the client.
   */
  @Test
  public void testConvertQuotation_overABlockQuota_rollsTheWholeConversionBack() throws Exception {
    Order order = orderWithStatus("CO", "DR");
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    when(process.convertQuotationIntoSalesOrder(false, "q-5528")).thenReturn(order);
    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(mock(Session.class));

    NeoResponse result;
    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class);
         MockedConstruction<DocumentCurrencyRate> rates =
             Mockito.mockConstruction(DocumentCurrencyRate.class);
         MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class);
         MockedStatic<NeoQuotaCreateCheck> quota = QuotaRefusalTestSupport.everyCreateRefused()) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      docAction.when(() -> OrderDocActionSupport.runDocAction(order, "RE")).thenReturn(true);

      result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-5528").build());

      quota.verify(() -> NeoQuotaCreateCheck.requireAllowedDocument(order));
    }

    QuotaRefusalTestSupport.assertQuotaExceeded(result);
    assertFalse(result.getBody().has("salesOrderId"));
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }

  /**
   * A failure the procedure REPORTS is best-effort: the conversion is kept, the order stays CO
   * and the response says so.
   */
  @Test
  public void testConvertQuotation_reactivationReportsFailure_keepsCompletedOrder()
      throws Exception {
    Order order = orderWithStatus("CO", "CO");
    try (MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class)) {
      docAction.when(() -> OrderDocActionSupport.runDocAction(order, "RE")).thenReturn(false);

      NeoResponse result = convertQuotation(order);

      assertEquals(200, result.getHttpStatus());
      assertEquals("order-5528", result.getBody().getString("salesOrderId"));
      assertEquals("CO", result.getBody().getString("documentStatus"));
    }
  }

  /**
   * A THROWN failure leaves the transaction aborted, so answering 200 with a salesOrderId that
   * will never be persisted would lie: the whole request must fail.
   */
  @Test
  public void testConvertQuotation_reactivationThrows_returnsError() throws Exception {
    Order order = orderWithStatus("CO", "CO");
    try (MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class)) {
      docAction.when(() -> OrderDocActionSupport.runDocAction(order, "RE"))
          .thenThrow(new java.sql.SQLException("statement timeout"));

      NeoResponse result = convertQuotation(order);

      assertEquals(500, result.getHttpStatus());
      assertFalse(result.getBody().has("salesOrderId"));
    }
  }

  /** An order core did not leave in CO is not reactivated. */
  @Test
  public void testConvertQuotation_orderNotCompleted_noReactivation() throws Exception {
    Order order = orderWithStatus("DR", "DR");
    try (MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class)) {

      NeoResponse result = convertQuotation(order);

      docAction.verify(() -> OrderDocActionSupport.runDocAction(any(), anyString()), never());
      assertEquals(200, result.getHttpStatus());
      assertEquals("DR", result.getBody().getString("documentStatus"));
    }
  }

  // ── ETP-5840: the converted order's rate is recomputed, never copied ────────

  /**
   * The order {@code Convertquotation} creates gets its rate recomputed from its OWN date, with the
   * sales order's rate rules, after the reactivation left it in Draft: the order is refreshed and
   * handed to one {@link DocumentCurrencyRate#recompute}. The quotation's rate is not copied (the
   * {@code afterHandle} transfer was removed).
   */
  @Test
  public void testConvertQuotation_recomputesTheOrderRateAfterReactivation() throws Exception {
    Order order = orderWithStatus("CO", "DR");
    try (MockedStatic<OrderDocActionSupport> docAction =
             Mockito.mockStatic(OrderDocActionSupport.class)) {
      docAction.when(() -> OrderDocActionSupport.runDocAction(order, "RE")).thenReturn(true);

      NeoResponse result = convertQuotation(order, (rates, session) -> {
        assertEquals(1, rates.size());
        verify(rates.get(0)).recompute(order);
        verify(session).refresh(order);
      });

      docAction.verify(() -> OrderDocActionSupport.runDocAction(order, "RE"));
      assertEquals(200, result.getHttpStatus());
    }
  }

  /**
   * A foreign-currency quotation without a rate is not converted: the request answers the 422
   * completion refusal (organization and document ISO codes as params) and the conversion process
   * is never called — on every channel, since both REST and MCP reach this pre-hook.
   */
  @Test
  public void testConvertQuotation_foreignQuotationWithoutRate_isRefused() throws Exception {
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);

    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Currency usd = mock(Currency.class);
    when(usd.getId()).thenReturn("usd-id");
    Order quotation = mock(Order.class);
    when(quotation.get(Order.PROPERTY_ORGANIZATION)).thenReturn(org);
    when(quotation.get(Order.PROPERTY_CURRENCY)).thenReturn(usd);
    when(quotation.get(Order.PROPERTY_ETGOCURRENCYRATE)).thenReturn(null);
    OBDal dal = mock(OBDal.class);
    when(dal.get(Order.ENTITY_NAME, "q-fx")).thenReturn(quotation);

    try (MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> currencies = Mockito.mockStatic(OBCurrencyUtils.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      currencies.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-fx").build());

      assertEquals(422, result.getHttpStatus());
      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals(DocumentRateMessages.CODE_MISSING_ON_COMPLETE, error.getString("code"));
      JSONObject params = error.getJSONObject(NeoProcessService.MESSAGE_PARAMS);
      assertEquals("eur-id", params.getString("orgCurrency"));
      assertEquals("usd-id", params.getString("documentCurrency"));
      verify(process, never()).convertQuotationIntoSalesOrder(anyBoolean(), anyString());
    }
  }

  // ── afterHandle GET — total discount adjustment (ETP-4029 follow-up) ─────
  //
  // Exercised through the inherited AbstractOrderHeaderHandler#afterHandle GET path, which this
  // handler's override runs first (ETP-6002 only adds linkedShipments on the detail GET; the
  // reader's failure on these unstubbed read-only connections is logged and leaves the key out).

  private static SalesQuotationHeaderHandler handlerWithTotalDiscountMock(
      TotalDiscountService svc) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    return handler;
  }

  private static NeoContext getCtx(String recordId) {
    return NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(recordId).build();
  }

  private static JSONObject quotationRecordWithDiscount(boolean processed, double discount,
      double grandTotal) throws Exception {
    return new JSONObject().put("id", "quot-disc-1").put("processed", processed).put(
        "etgoTotalDiscount", discount).put("grandTotalAmount", grandTotal);
  }

  /** Stubs OBDal so the DB-backed hasLinkedDocuments check (run unconditionally after the
   *  discount loop) finds nothing, keeping these tests focused on the discount math. */
  private static void stubNoLinkedDocuments(MockedStatic<OBDal> obDalMock) throws Exception {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);
  }

  @Test
  public void testAfterHandle_processedQuotation_notAdjusted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(true, 10.0, 198.99))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  @Test
  public void testAfterHandle_draftQuotationWithNoDiscount_notAdjusted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 0.0, 221.10))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(221.10, grand, 0.001);
    }
  }

  @Test
  public void testAfterHandle_draftQuotationWithMaterializedDiscountLine_notAdjustedTwice()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("quot-disc-1", false)).thenReturn(true);
      SalesQuotationHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 10.0, 198.99))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft quotation with etgoTotalDiscount=10 and grandTotalAmount=221.10 is
   * adjusted to 198.99 (221.10 x 0.90) via the shared AbstractOrderHeaderHandler logic — the
   * same gap confirmed manually on sales-order #1000207 before this fix.
   */
  @Test
  public void testAfterHandle_draftQuotationWithDiscount_adjustsGrandTotal() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("quot-disc-1", false)).thenReturn(false);
      SalesQuotationHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 10.0, 221.10))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.005);
    }
  }

  // ── afterHandle GET — linkedShipments (QuotationShipmentLinks) ────────────

  /**
   * {@code linkedShipments} — the shipments whose header or lines point at the quotation — is read
   * on the detail GET only: never on the list, and a failed read leaves the key out without
   * failing the GET. One row per case: {@code label, recordId, readFails, expectedShipments}
   * ({@code -1} = key absent).
   */
  @Test
  public void testAfterHandle_linkedShipmentsOnDetailGetOnly() throws Exception {
    Object[][] cases = {
        { "detail GET", "quot-ship-1", false, 1 },
        { "list GET", null, false, -1 },
        { "detail GET with a failed read", "quot-ship-1", true, -1 },
    };
    for (Object[] row : cases) {
      String label = (String) row[0];
      String recordId = (String) row[1];
      boolean readFails = (Boolean) row[2];
      int expected = (Integer) row[3];
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        stubNoLinkedDocuments(obDalMock);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        PreparedStatement otherPs = mock(PreparedStatement.class);
        when(otherPs.executeQuery()).thenReturn(emptyRs);
        ResultSet shipmentsRs = mock(ResultSet.class);
        when(shipmentsRs.next()).thenReturn(true, false);
        when(shipmentsRs.getString(1)).thenReturn("ship-1");
        when(shipmentsRs.getString(2)).thenReturn("SH-1");
        when(shipmentsRs.getString(3)).thenReturn("CO");
        when(shipmentsRs.getString(4)).thenReturn("N");
        PreparedStatement shipmentsPs = mock(PreparedStatement.class);
        when(shipmentsPs.executeQuery()).thenReturn(shipmentsRs);
        Connection readOnlyConn = mock(Connection.class);
        when(readOnlyConn.prepareStatement(anyString())).thenReturn(otherPs);
        if (readFails) {
          when(readOnlyConn.prepareStatement(QuotationShipmentLinks.SQL))
              .thenThrow(new java.sql.SQLException("read failed"));
        } else {
          when(readOnlyConn.prepareStatement(QuotationShipmentLinks.SQL)).thenReturn(shipmentsPs);
        }
        OBDal readOnlyDal = mock(OBDal.class);
        when(readOnlyDal.getConnection()).thenReturn(readOnlyConn);
        obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);

        JSONObject body = new JSONObject().put("response", new JSONObject().put("data",
            new JSONArray().put(quotationRecordWithDiscount(true, 0.0, 100.0))));
        NeoContext ctx = getCtx(recordId);
        ctx.setPreviousResult(NeoResponse.ok(body));

        NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

        JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
        if (expected < 0) {
          assertFalse(label, rec.has(QuotationShipmentLinks.FIELD_LINKED_SHIPMENTS));
        } else {
          assertNotNull(label, result);
          JSONArray shipments = result.getBody().getJSONObject("response").getJSONArray("data")
              .getJSONObject(0).getJSONArray(QuotationShipmentLinks.FIELD_LINKED_SHIPMENTS);
          assertEquals(label, expected, shipments.length());
          assertEquals(label, "ship-1", shipments.getJSONObject(0).getString("id"));
          assertEquals(label, "SH-1", shipments.getJSONObject(0).getString("documentNo"));
          assertFalse(label, shipments.getJSONObject(0).getBoolean("isReturn"));
        }
        if (recordId == null) {
          verify(readOnlyConn, never()).prepareStatement(QuotationShipmentLinks.SQL);
        } else if (readFails) {
          assertNotNull(label + ": a failed read must not fail the GET", result);
        }
      }
    }
  }

  /** The injected key is declared, so the response field filter keeps it. */
  @Test
  public void testResponseEnrichedFields_declaresLinkedShipments() {
    assertTrue(new SalesQuotationHeaderHandler().responseEnrichedFields()
        .contains(QuotationShipmentLinks.FIELD_LINKED_SHIPMENTS));
  }

  // ── ETP-5238: paymentMethod SELECTOR is independent of Financial Account ────

  /**
   * A SELECTOR context wired with an AD Tab/Window pair whose {@code isSalesTransaction()} is
   * {@code true} — the sales-quotation window declares {@link
   * com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback#WINDOW},
   * so this is what the handler resolves direction from once the {@code IsSOTrx} request param
   * is absent.
   */
  private static NeoContext salesWindowSelectorCtx() {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(true);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("sales-quotation").entityName("quotation").httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR).fieldName("paymentMethod")
        .adTab(tab).build();
  }

  private void stubPaymentMethodSelectorRequest(MockedStatic<RequestContext> reqCtx,
      Map<String, String> params) {
    RequestContext requestContext = mock(RequestContext.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    reqCtx.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(request);
    when(request.getParameter(anyString())).thenReturn(null);

    Map<String, String[]> parameterMap = new HashMap<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      parameterMap.put(entry.getKey(), new String[] { entry.getValue() });
    }
    when(request.getParameterMap()).thenReturn(parameterMap);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Criterion> stubPaymentMethodCriteria(MockedStatic<OBDal> obDal,
      List<FIN_PaymentMethod> rows) {
    OBDal obDalMock = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalMock);
    OBCriteria<FIN_PaymentMethod> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(FIN_PaymentMethod.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.addOrderBy(any(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.setFirstResult(anyInt())).thenReturn(criteria);
    when(criteria.count()).thenReturn(rows.size());
    when(criteria.list()).thenReturn(rows);
    return captor;
  }

  /** Renders every captured restriction (read only AFTER invoking the method under test). */
  private List<String> renderedCriteria(ArgumentCaptor<Criterion> captor) {
    return captor.getAllValues().stream().map(Criterion::toString)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * The paymentMethod SELECTOR request must be served by
   * {@code com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport}, short-circuiting
   * before any dispatch to the injected ACTION delegates, and — since this window declares the
   * {@code WINDOW} direction fallback — the criteria built when {@code IsSOTrx} is absent must be
   * against {@code payinAllow}, never {@code payoutAllow}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorIndependentOfFinancialAccount() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      stubPaymentMethodSelectorRequest(reqCtx, Collections.emptyMap());

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesQuotationHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Same window, but with the request explicitly sending {@code IsSOTrx=Y} (the normal case in
   * production) — must resolve identically to the absent-param case above.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorBuildsPayinCriteriaWhenIsSOTrxIsY() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "Y");
      stubPaymentMethodSelectorRequest(reqCtx, params);

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesQuotationHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Non-selector requests are completely unaffected by the new SELECTOR branch (no-regression).
   */
  @Test
  public void testHandleStillReturnsNullForNonSelectorRequestAfterPaymentMethodWiring() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID).build();
    assertNull(new SalesQuotationHeaderHandler().handle(ctx));
  }

  /**
   * ETP-5703: the point-4 QA case came from quotations (ETP-5535). A batch create with only
   * {@code businessPartner}, for a partner with a single location, gets partnerAddress and
   * invoiceAddress from it — the same pre-hook default as the sales order.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleCreateWithSingleLocationFillsPartnerAndInvoiceAddress() throws Exception {
    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDal =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal =
          org.mockito.Mockito.mock(org.openbravo.dal.service.OBDal.class);
      obDal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      org.openbravo.model.common.businesspartner.Location only =
          org.mockito.Mockito.mock(org.openbravo.model.common.businesspartner.Location.class);
      org.mockito.Mockito.when(only.getId()).thenReturn("loc-1");
      org.openbravo.dal.service.OBCriteria<org.openbravo.model.common.businesspartner.Location>
          criteria = org.mockito.Mockito.mock(org.openbravo.dal.service.OBCriteria.class);
      org.mockito.Mockito.when(
          dal.createCriteria(org.openbravo.model.common.businesspartner.Location.class))
          .thenReturn(criteria);
      org.mockito.Mockito.when(criteria.add(org.mockito.ArgumentMatchers.any()))
          .thenReturn(criteria);
      java.util.List<org.openbravo.model.common.businesspartner.Location> locations =
          java.util.Collections.singletonList(only);
      org.mockito.Mockito.when(criteria.list()).thenReturn(locations);
      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder().httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD).requestBody(body).build();

      org.junit.Assert.assertNull(new SalesQuotationHeaderHandler().handle(ctx));

      org.junit.Assert.assertEquals("loc-1", body.getString("partnerAddress"));
      org.junit.Assert.assertEquals("loc-1", body.getString("invoiceAddress"));
    }
  }

  // ── declared action contracts (ETP-5709) ──────────────────────────────────

  /** createDraftInvoice is published next to the reject actions, which stay declared. */
  @Test
  public void actionContracts_declaresInvoiceAndKeepsRejectActions() {
    Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts =
        new SalesQuotationHeaderHandler().actionContracts();

    assertEquals(List.of("createDraftInvoice", "rejectQuotation", "createRejectReason"),
        List.copyOf(contracts.keySet()));
    com.etendoerp.go.schemaforge.util.NeoActionContract invoice = contracts.get("createDraftInvoice");
    assertSame(CreateDraftInvoiceHandler.QUOTATION_CONTRACT, invoice);
    assertTrue("a quotation is invoiced in full: no parameters", invoice.getParams().isEmpty());
    String description = invoice.getDescription();
    assertTrue("must warn the invoice is completed despite the name",
        description.contains("COMPLETES") && description.contains("despite the name"));
    assertTrue("must name the status the quotation ends in", description.contains("ETGO_CI"));
    assertTrue("must name the required status", description.contains("UE"));
  }

  /** The body the SPA posts ({}) and a valid partial body pass; an unknown key is a 422. */
  @Test
  public void actionContracts_acceptTheSpaBodyAndRejectUnknownKeys() throws Exception {
    Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts =
        new SalesQuotationHeaderHandler().actionContracts();

    assertNull(com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
        "createDraftInvoice", new JSONObject()));
    assertEquals(com.etendoerp.go.schemaforge.util.NeoActionContract.SC_UNPROCESSABLE,
        com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
            "createDraftInvoice", new JSONObject().put("lines",
                new JSONArray().put(new JSONObject().put("orderLineId", "L1").put("quantity", "2"))))
            .getHttpStatus());
    assertEquals(com.etendoerp.go.schemaforge.util.NeoActionContract.SC_UNPROCESSABLE,
        com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
            "createDraftInvoice", new JSONObject().put("priceListId", "P1")).getHttpStatus());
  }
}
