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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.API_KEY;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.CLIENT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.ORIGIN;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;

import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeConnectionBuilder;

/**
 * Unit specs for the sandbox/fake bank gate of the {@code connect} action (ETP-5344).
 *
 * <p>Salt Edge's test banks exist so a Demo tenant can rehearse the connection flow without real
 * credentials. A tenant that paid for its plan connects its actual bank and has no use for them, so
 * it must never be offered one — not even when an operator left {@code PSD2_ShowFakeProviders}
 * enabled instance-wide. The decision is therefore two conditions, and the answer travels to the
 * PSD2 module as the fourth argument of {@code createSaltEdgeConnection} rather than being
 * re-derived there from the preference alone.
 *
 * <p>The gate itself is private, so every case is exercised through {@code POST connect} and
 * asserted on the argument the PSD2 module actually receives — which is the behaviour that matters:
 * a gate that computed the right boolean and failed to pass it on would be indistinguishable from
 * no gate at all.
 *
 * <p>Kept apart from {@code FinancialAccountBankConnectionHandlerConnectTest} to stay under the
 * Sonar 35-method-per-class limit, following the split already used across this handler's suites.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerSandboxTest {

  private static final String CALLBACK = "/financial-account/bank-connection-callback";
  private static final String ACTION_CONNECT = "connect";
  private static final String CONNECT_URL = "https://saltedge.example/connect/abc";

  /** No extra static stubbing beyond what every case in this class needs. */
  private static final Consumer<MockedStatic<OBDal>> NO_EXTRA_SETUP = obDal -> {
  };

  @Mock
  private TenantPlanService tenantPlanService;

  private FinancialAccountBankConnectionHandler handler;

  @Before
  public void setUp() {
    handler = spy(new FinancialAccountBankConnectionHandler(tenantPlanService));
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /**
   * Preference enabled and a Demo (free) tenant — the case the feature exists for: the widget is
   * asked for sandbox banks, and the plan of the CALLING tenant is the one consulted.
   */
  @Test
  public void testDemoTenantIsOfferedSandboxBanks() throws Exception {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);

    assertTrue(connectAndCaptureSandboxFlag(handler, true, NO_EXTRA_SETUP));
    verify(tenantPlanService).resolvePlan(CLIENT_ID);
  }

  /**
   * A tenant carrying no plan marker at all — every environment provisioned before the marker
   * existed, and every first unpaid tenant. It must behave exactly like an explicit free tenant.
   *
   * <p>Uses the REAL {@link TenantPlanService} through the no-argument (CDI) constructor, with the
   * preference lookup resolving to no row: the "absent means free" default is what this case is
   * about, so stubbing it away would assert nothing. It also covers the CDI wiring — a handler
   * built the way the container builds it reaches a working plan service.
   */
  @Test
  public void testTenantWithNoPlanMarkerIsTreatedAsDemo() throws Exception {
    FinancialAccountBankConnectionHandler cdiHandler =
        spy(new FinancialAccountBankConnectionHandler());
    doNothing().when(cdiHandler).doRollbackAndClose();

    assertTrue(connectAndCaptureSandboxFlag(cdiHandler, true, obDal -> {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBQuery<Preference> query = mock(OBQuery.class);
      when(dal.createQuery(eq(Preference.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);
    }));
  }

  /**
   * The point of ETP-5344: a tenant that paid for its plan gets no test banks even though
   * {@code PSD2_ShowFakeProviders} is enabled. The preference alone used to decide this.
   */
  @Test
  public void testProductiveTenantIsNeverOfferedSandboxBanksDespiteTheEnabledPreference()
      throws Exception {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    assertFalse(connectAndCaptureSandboxFlag(handler, true, NO_EXTRA_SETUP));
  }

  /**
   * The preference stays the necessary condition: with it disabled a Demo tenant sees no test banks
   * either. This module never overrides an operator who turned the PSD2 switch off — and it does
   * not even look the plan up, so a disabled instance pays no query for the decision.
   */
  @Test
  public void testDisabledPreferenceHidesSandboxBanksFromADemoTenant() throws Exception {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);

    assertFalse(connectAndCaptureSandboxFlag(handler, false, NO_EXTRA_SETUP));
    verify(tenantPlanService, never()).resolvePlan(anyString());
  }

  /** Both conditions failing is still a plain "no sandboxes" — the two gates do not cancel out. */
  @Test
  public void testDisabledPreferenceHidesSandboxBanksFromAProductiveTenant() throws Exception {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    assertFalse(connectAndCaptureSandboxFlag(handler, false, NO_EXTRA_SETUP));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Runs {@code POST connect} against the given handler and returns the {@code includeSandboxes}
   * argument it handed to the PSD2 module.
   *
   * @param target
   *     the handler under test (built with either constructor)
   * @param fakeProvidersEnabled
   *     the value {@code BankIntegrationUtils.isFakeProvidersEnabled()} resolves to, i.e. the
   *     {@code PSD2_ShowFakeProviders} preference
   * @param extraSetup
   *     further stubbing applied to the open {@link OBDal} static mock, for the case that exercises
   *     the real {@link TenantPlanService}
   * @return the fourth argument of the {@code createSaltEdgeConnection} call
   */
  private boolean connectAndCaptureSandboxFlag(FinancialAccountBankConnectionHandler target,
      boolean fakeProvidersEnabled, Consumer<MockedStatic<OBDal>> extraSetup) throws Exception {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeConnectionBuilder> builder =
            mockStatic(SaltEdgeConnectionBuilder.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext);
      extraSetup.accept(obDal);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(BankIntegrationUtils::isFakeProvidersEnabled).thenReturn(fakeProvidersEnabled);
      builder.when(() -> SaltEdgeConnectionBuilder.createSaltEdgeConnection(anyString(), anyString(),
          any(), anyBoolean())).thenReturn(CONNECT_URL);

      NeoResponse response = target.handle(postContext(ACTION_CONNECT, new JSONObject()));

      assertEquals(200, response.getHttpStatus());
      assertEquals(CONNECT_URL, response.getBody().getJSONObject("response")
          .getJSONObject("data").getString("connectUrl"));

      ArgumentCaptor<Boolean> includeSandboxes = ArgumentCaptor.forClass(Boolean.class);
      builder.verify(() -> SaltEdgeConnectionBuilder.createSaltEdgeConnection(eq(API_KEY),
          eq(ORIGIN + CALLBACK), isNull(), includeSandboxes.capture()));
      return includeSandboxes.getValue();
    }
  }

  /** Stubs the {@code Origin} header the SPA callback URL is built from. */
  private static void stubOrigin(MockedStatic<RequestContext> requestContext) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Origin")).thenReturn(ORIGIN);
    RequestContext rc = mock(RequestContext.class);
    when(rc.getRequest()).thenReturn(request);
    requestContext.when(RequestContext::get).thenReturn(rc);
  }
}
