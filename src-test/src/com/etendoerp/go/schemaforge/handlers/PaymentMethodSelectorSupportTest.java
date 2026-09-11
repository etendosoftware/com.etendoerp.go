/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback;

/**
 * Unit tests for {@link PaymentMethodSelectorSupport} (ETP-5238).
 *
 * <p>Covers: the field-name/endpoint-type guard, the {@code IsSOTrx}-wins-over-declared-fallback
 * direction priority (checked before {@link DirectionFallback} is even consulted), the
 * {@code FIN_ISRECEIPT} alias (contacts parity), the {@link DirectionFallback#FIELD_NAME} policy
 * (contacts — unchanged), the {@link DirectionFallback#WINDOW} policy (the 5 document windows —
 * the actual regression fix), and the fail-closed contract when {@code WINDOW} cannot resolve a
 * direction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentMethodSelectorSupportTest {

  private static NeoContext selectorCtx(String fieldName) {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName(fieldName)
        .build();
  }

  private static NeoContext crudCtx() {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * A SELECTOR context wired with an AD Tab/Window pair, for exercising the
   * {@link DirectionFallback#WINDOW} policy. {@code isSalesTransaction} may be {@code null} to
   * simulate an unresolved flag.
   */
  private static NeoContext windowBackedSelectorCtx(String fieldName, Boolean isSalesTransaction) {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(isSalesTransaction);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("purchase-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName(fieldName)
        .adTab(tab)
        .build();
  }

  // ── guard: not our case → null ────────────────────────────────────────────

  @Test
  void testReturnsNullForNullContext() {
    assertNull(PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(null, DirectionFallback.FIELD_NAME));
  }

  @Test
  void testReturnsNullForNonSelectorEndpointType() {
    assertNull(PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(crudCtx(), DirectionFallback.FIELD_NAME));
  }

  @Test
  void testReturnsNullForUnrelatedSelectorField() {
    NeoContext ctx = selectorCtx("salesRepresentative");
    assertNull(PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME));
  }

  // ── direction from request param wins over the declared fallback ─────────

  @Test
  @SuppressWarnings("unchecked")
  void testIsSOTrxYResolvesPayInEvenThoughFieldNameIsAmbiguous() throws Exception {
    // WINDOW fallback declared but no adTab/window wired — the request param must still win,
    // proving IsSOTrx is checked before the declared fallback is even consulted.
    NeoContext ctx = selectorCtx("paymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "Y");
      stubSelectorRequest(reqCtx, params);

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.WINDOW);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      assertPayInOnly(criteria);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testIsSOTrxNResolvesPayOutEvenThoughFieldNameLooksLikePayIn() throws Exception {
    // paymentMethod is the field name every document window uses for BOTH directions —
    // IsSOTrx=N (purchase document) must still resolve to pay-out, even under FIELD_NAME
    // fallback, because the request param is checked first regardless of the declared policy.
    NeoContext ctx = selectorCtx("paymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "N");
      stubSelectorRequest(reqCtx, params);

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME);

      assertNotNull(response);
      assertPayOutOnly(criteria);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testFinIsReceiptYResolvesPayIn() throws Exception {
    NeoContext ctx = selectorCtx("paymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("FIN_ISRECEIPT", "Y");
      stubSelectorRequest(reqCtx, params);

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME);

      assertNotNull(response);
      assertPayInOnly(criteria);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testFinIsReceiptNResolvesPayOut() throws Exception {
    NeoContext ctx = selectorCtx("paymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("FIN_ISRECEIPT", "N");
      stubSelectorRequest(reqCtx, params);

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME);

      assertNotNull(response);
      assertPayOutOnly(criteria);
    }
  }

  // ── FIELD_NAME fallback (contacts) when no direction param is present ────

  @Test
  @SuppressWarnings("unchecked")
  void testFieldNameFallbackPaymentMethodWhenNoDirectionParamResolvesPayIn() throws Exception {
    // Locks in the FIELD_NAME contract only — the contacts window's own field-name pair already
    // distinguishes the two directions unambiguously, so this default is correct there.
    NeoContext ctx = selectorCtx("paymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME);

      assertNotNull(response);
      assertPayInOnly(criteria);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testFieldNameFallbackPoPaymentMethodWhenNoDirectionParam() throws Exception {
    NeoContext ctx = selectorCtx("pOPaymentMethod");
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.FIELD_NAME);

      assertNotNull(response);
      assertPayOutOnly(criteria);
    }
  }

  // ── WINDOW fallback (the 5 document windows) ──────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void testWindowFallbackSalesWindowResolvesPayInWhenNoDirectionParam() throws Exception {
    NeoContext ctx = windowBackedSelectorCtx("paymentMethod", true);
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.WINDOW);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      assertPayInOnly(criteria);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testWindowFallbackPurchaseWindowResolvesPayOutWhenNoDirectionParam() throws Exception {
    // The exact regression this fix closes: the same "paymentMethod" field name, on a purchase
    // window, with no IsSOTrx param — must resolve to pay-out, not the old PAY_IN catch-all.
    NeoContext ctx = windowBackedSelectorCtx("paymentMethod", false);
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      OBCriteria<FIN_PaymentMethod> criteria = stubCriteria(obDal, Collections.emptyList());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.WINDOW);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      assertPayOutOnly(criteria);
    }
  }

  // ── fail closed: WINDOW fallback that cannot resolve a direction ─────────

  @Test
  void testWindowFallbackWithNoTabFailsClosedInsteadOfDefaultingToPayIn() {
    NeoContext ctx = selectorCtx("paymentMethod"); // no adTab wired
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.WINDOW);

      assertNotNull(response);
      assertEquals(422, response.getHttpStatus());
    }
  }

  @Test
  void testWindowFallbackWithNullIsSalesTransactionFailsClosed() {
    NeoContext ctx = windowBackedSelectorCtx("paymentMethod", null);
    try (MockedStatic<RequestContext> reqCtx = mockStatic(RequestContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      NeoResponse response =
          PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(ctx, DirectionFallback.WINDOW);

      assertNotNull(response);
      assertEquals(422, response.getHttpStatus());
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void stubSelectorRequest(MockedStatic<RequestContext> reqCtx, Map<String, String> params) {
    RequestContext requestContext = mock(RequestContext.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    reqCtx.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(request);
    when(request.getParameter(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

    Map<String, String[]> parameterMap = new HashMap<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      parameterMap.put(entry.getKey(), new String[] { entry.getValue() });
    }
    when(request.getParameterMap()).thenReturn(parameterMap);
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<FIN_PaymentMethod> stubCriteria(MockedStatic<OBDal> obDal,
      List<FIN_PaymentMethod> rows) {
    OBDal obDalMock = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalMock);

    OBCriteria<FIN_PaymentMethod> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(FIN_PaymentMethod.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(any(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.setFirstResult(anyInt())).thenReturn(criteria);
    when(criteria.count()).thenReturn(rows.size());
    when(criteria.list()).thenReturn(rows);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private List<Object> capturedRestrictions(OBCriteria<FIN_PaymentMethod> criteria) {
    ArgumentCaptor<org.hibernate.criterion.Criterion> captor =
        ArgumentCaptor.forClass(org.hibernate.criterion.Criterion.class);
    verify(criteria, org.mockito.Mockito.atLeastOnce()).add(captor.capture());
    return (List<Object>) (List<?>) captor.getAllValues();
  }

  private void assertPayInOnly(OBCriteria<FIN_PaymentMethod> criteria) {
    List<Object> restrictions = capturedRestrictions(criteria);
    assertTrue(restrictions.stream().anyMatch(r -> r.toString().toLowerCase().contains("payinallow")));
    assertFalse(restrictions.stream().anyMatch(r -> r.toString().toLowerCase().contains("payoutallow")));
    assertFalse(restrictions.stream()
        .anyMatch(r -> r.toString().toLowerCase().contains("finaccpaymentmethod")));
  }

  private void assertPayOutOnly(OBCriteria<FIN_PaymentMethod> criteria) {
    List<Object> restrictions = capturedRestrictions(criteria);
    assertTrue(restrictions.stream().anyMatch(r -> r.toString().toLowerCase().contains("payoutallow")));
    assertFalse(restrictions.stream().anyMatch(r -> r.toString().toLowerCase().contains("payinallow")));
    assertFalse(restrictions.stream()
        .anyMatch(r -> r.toString().toLowerCase().contains("finaccpaymentmethod")));
  }
}
