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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.Cookie;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.EnvironmentPlanCache;
import com.etendoerp.go.payment.SubscriptionModelSwitch;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.TenantPaywallService;
import com.etendoerp.go.onboarding.OnboardingCompanyProfileTransferService;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Additional unit tests for {@link EtendoGoJwtServlet} targeting branch and exception
 * paths that the primary {@code EtendoGoJwtServletTest} suite does not exercise:
 * login/register success and database-error paths, the SSO update/conflict/error branches,
 * the change-password and password-reset validation branches, the /environments data-mapping
 * loop, the GET /login (environment login) success and user-not-found paths, and the
 * onboarding pre-flight helpers (resolveCurrencyId, parseOnboardingRequest,
 * writeEnvironmentLoginResponse).
 *
 * <p>It also owns the specs for {@code applyPaidUpgradeSideEffects} (ETP-5046): which store records
 * a paid tenant's plan — decided first by the environment's subscription-model switch, then by
 * whether the row could be opened — and that the per-tenant retirement of the legacy
 * ETGO_TenantPlan preference can never fail an upgrade that has already been paid for.
 *
 * <p>And the specs for the scoped system context of the two ETP-5548 checks,
 * {@code isProductiveNameTakenByAccount} and {@code isAssociatedDemo}: each runs as System and
 * hands the caller back its own OBContext and admin-mode depth, on every path.
 *
 * <p>And the specs for {@code fillSubscriptionPeriodBestEffort} (ETP-5047): a paid onboarding reads
 * its new subscription's billing period after the onboarding commit, commits it on its own, and
 * survives any failure of that step.
 *
 * <p>And the purchase-source eligibility specs (ETP-5548, ETP-5047): a spent demo or a productive
 * environment, a canceled one included, is never the demo source of a purchase.
 *
 * <p>And the specs for {@code provisionPaidPurchase} (ETP-5048), the browser-less paid onboarding
 * the billing reconciliation drives through {@code PaidProvisioningService}: it runs under the
 * same fenced {@code claimForProvisioning} as the HTTP path, a refused claim runs nothing, and the
 * NDJSON progress reaches the given sink. The draft's address and fiscal identifier reach the
 * organization, and a value the HTTP path would refuse is dropped with a WARN instead of failing
 * the run. A lost paid re-subscription is applied through {@code PaidReactivationService} over
 * the webhook's own {@code applyReactivationIfPending}.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 * @covers com.etendoerp.go.rest.PaidReactivationService
 */
public class EtendoGoJwtServletCoverageTest {

  /** ETP-5548: the names a classic-path client is created and resumed under. */
  private static final String PAID_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "purchase-1");
  private static final String OTHER_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "purchase-2");
  private static final String FREE_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "");

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));

  private MockedStatic<org.openbravo.dal.core.SessionHandler> requestSession;
  private org.openbravo.dal.core.SessionHandler unitSession;

  @Before
  public void isolateInternalAlertDelivery() {
    unitSession = mock(org.openbravo.dal.core.SessionHandler.class);
    requestSession = mockStatic(org.openbravo.dal.core.SessionHandler.class);
    requestSession.when(org.openbravo.dal.core.SessionHandler::getInstance).thenReturn(unitSession);
    servlet.internalAlertService = mock(com.etendoerp.go.schemaforge.email.InternalAlertService.class);
    // The real switch reads AD_Preference. The paid-upgrade specs below were written for the
    // subscription model, so it is ON unless a spec says otherwise.
    servlet.subscriptionModelSwitch = switchThatIs(true);
  }

  private static SubscriptionModelSwitch switchThatIs(boolean on) {
    SubscriptionModelSwitch modelSwitch = mock(SubscriptionModelSwitch.class);
    when(modelSwitch.isOn()).thenReturn(on);
    return modelSwitch;
  }

  @After
  public void closeUnitSession() {
    requestSession.close();
  }

  @Test
  public void paidRetryUsesThePersistedDemoEvenWhenSeveralFreeDemosExist() {
    Set<String> currentFreeDemos = Set.of("TRIAL-SELECTED", "TRIAL-OTHER");

    String resolved = EtendoGoJwtServlet.resolvePaidDemoClientId(
        true, "TRIAL-SELECTED", currentFreeDemos);

    assertEquals("The persisted checkout selection remains authoritative on retry",
        "TRIAL-SELECTED", resolved);
  }

  @Test
  public void paidRetryRejectsPersistedDemoWhenItIsNoLongerOwnedOrFree() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> EtendoGoJwtServlet.resolvePaidDemoClientId(
            true, "TRIAL-SELECTED", Set.of("TRIAL-OTHER")));

    assertTrue(error.getMessage().contains("no longer available"));
  }

  @Test
  public void legacyPaidRetryWithoutRecordedSelectionNeverInfersADemo() {
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(false, null, Set.of("TRIAL-ONLY")));
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(false, null, Set.of()));
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(
        false, null, Set.of("TRIAL-1", "TRIAL-2")));
  }

  @Test
  public void explicitlyEmptyDemoSelectionDoesNotInferADemoThatAppearsLater() {
    // A recorded null is an intentional productive-origin purchase, unlike a legacy row with
    // no recorded-selection marker. Neither case may infer a source from the current demo set.
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(
        true, null, Set.of("TRIAL-ADDED-AFTER-CHECKOUT")));
  }

  @Test
  public void paidOnboardingUsesPersistedDemoAndLegacySkipsTransfers()
      throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    OnboardingCompanyProfileTransferService profileTransfer =
        mock(OnboardingCompanyProfileTransferService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.onboardingCompanyProfileTransferService = profileTransfer;
    when(lifecycle.associateDemoWithProductive("STORED-DEMO", "NEW-PRODUCTIVE")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true, false);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    Object recorded = prepareOnboardingForPersistedSelection();
    Object recordedRequest = getField(recorded, "request");
    assertEquals("STORED-DEMO", getField(recordedRequest, "demoClientId"));
    invokeProfileTransfer("STORED-DEMO", "NEW-PRODUCTIVE");

    Object legacy = prepareOnboardingForPersistedSelection();
    Object legacyRequest = getField(legacy, "request");
    assertNull(getField(legacyRequest, "demoClientId"));
    invokeProfileTransfer(null, "LEGACY-PRODUCTIVE");
    servlet.startDemoDataTransferBestEffort("purchase-1", null, "LEGACY-PRODUCTIVE",
        "account-1", "user@test.com");
    verify(lifecycle, times(1)).associateDemoWithProductive("STORED-DEMO", "NEW-PRODUCTIVE");
    verify(lifecycle, times(1)).isAssociatedWithProductive("STORED-DEMO");
    // ETP-5047: the persisted source is checked for being a productive environment too.
    verify(lifecycle, times(1)).isProductiveEnvironment("STORED-DEMO");
    verifyNoMoreInteractions(lifecycle);
    verify(profileTransfer, times(1)).copy("STORED-DEMO", "NEW-PRODUCTIVE", "ORG-1");
    verifyNoMoreInteractions(profileTransfer);
    verify(store, times(1)).findDemoClientId("purchase-1", "account-1", "user@test.com");
  }

  /**
   * ETP-5548: two purchases started from one demo. The first to finish took the demo as its
   * origin; the second is already paid, so it is not refused — it becomes a clean productive
   * environment with no source, transfer or demo revocation.
   */
  @Test
  public void paidSetupFromAnAlreadyAssociatedDemoContinuesWithoutASource() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("STORED-DEMO")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    Object prepared = prepareOnboardingForPersistedSelection();

    assertNull("A spent demo is never the origin of a second productive environment",
        getField(getField(prepared, "request"), "demoClientId"));
    verify(lifecycle, never()).associateDemoWithProductive(anyString(), anyString());
  }

  /**
   * ETP-5047: a purchase that persisted a canceled productive environment as its source (recorded
   * before eligibility excluded productive environments) is already paid, so it is not refused —
   * like the spent demo above, it becomes a clean productive environment with no source: the
   * current free demos are not even looked up (no 409 DEMO_SELECTION_REQUIRED can follow), and
   * neither the company profile copy nor the demo data transfer starts.
   */
  @Test
  public void paidSetupFromAProductiveSourceContinuesWithoutASource() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    OnboardingCompanyProfileTransferService profileTransfer =
        mock(OnboardingCompanyProfileTransferService.class);
    servlet.onboardingCompanyProfileTransferService = profileTransfer;
    DemoDataTransferService dataTransfer = mock(DemoDataTransferService.class);
    servlet.demoDataTransferService = dataTransfer;
    when(lifecycle.isProductiveEnvironment("STORED-DEMO")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    AtomicInteger answeredStatus = new AtomicInteger(-1);
    AtomicReference<String> answeredBody = new AtomicReference<>();

    Object prepared = prepareOnboardingForPersistedSelection((dal, response) -> {
      dal.verify(() -> EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail(anyString()), never());
      answeredStatus.set(response.status);
      answeredBody.set(response.body());
    });

    Object preparedRequest = getField(prepared, "request");
    assertNull("A productive environment is never the origin of another productive environment",
        getField(preparedRequest, "demoClientId"));
    assertEquals("nothing is answered while the paid setup goes on, least of all a 409",
        0, answeredStatus.get());
    assertEquals("", answeredBody.get());

    // The two downstream steps receive the source exactly as the paid setup derives it.
    Method sourceOf = EtendoGoJwtServlet.class.getDeclaredMethod("resolveDemoSourceClientId",
        boolean.class, preparedRequest.getClass());
    sourceOf.setAccessible(true);
    String demoSourceClientId = (String) sourceOf.invoke(null, true, preparedRequest);
    assertNull(demoSourceClientId);
    invokeProfileTransfer(demoSourceClientId, "NEW-PRODUCTIVE");
    servlet.startDemoDataTransferBestEffort("purchase-1", demoSourceClientId, "NEW-PRODUCTIVE",
        "account-1", "user@test.com");

    verify(lifecycle, never()).associateDemoWithProductive(anyString(), anyString());
    verifyNoInteractions(profileTransfer, dataTransfer);
    verify(store, never()).findTransferSelection(anyString(), anyString(), anyString());
  }

  /**
   * ETP-5548: a refused claim says why. A failure no retry can fix and a finished environment are
   * final, so they must not answer "still running", which invites the customer to keep retrying.
   */
  @Test
  public void refusedClaimExplainsAFinalStateInsteadOfStillRunning() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    CheckoutRequest nameInUse = mock(CheckoutRequest.class);
    when(nameInUse.getFailureReason()).thenReturn(ProvisioningFailureReason.encode(
        ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE, "raw cause"));
    when(store.deriveProvisioningStatus(nameInUse))
        .thenReturn(CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED);
    when(store.isProvisioningRetryAllowed(nameInUse)).thenReturn(false);
    CheckoutRequest provisioned = mock(CheckoutRequest.class);
    when(store.deriveProvisioningStatus(provisioned))
        .thenReturn(CheckoutRequestStore.DERIVED_STATUS_PROVISIONED);
    CheckoutRequest running = mock(CheckoutRequest.class);
    when(store.deriveProvisioningStatus(running)).thenReturn("provisioning");

    JSONObject notRetryable = claimRefusal(nameInUse);
    assertEquals("PROVISIONING_RETRY_NOT_ALLOWED", notRetryable.getString("code"));
    assertTrue(notRetryable.getString("userMessage").startsWith(
        "The account already has a productive environment with this company name"));
    assertFalse("The raw cause never reaches the customer",
        notRetryable.toString().contains("raw cause"));
    assertEquals("PROVISIONING_ALREADY_COMPLETED", claimRefusal(provisioned).getString("code"));
    assertEquals("PROVISIONING_ALREADY_IN_PROGRESS", claimRefusal(running).getString("code"));
  }

  private JSONObject claimRefusal(CheckoutRequest checkoutRequest) throws Exception {
    ResponseCapture response = mockResponse();
    Method refusal = EtendoGoJwtServlet.class.getDeclaredMethod("writeClaimRefusal",
        HttpServletResponse.class, CheckoutRequest.class);
    refusal.setAccessible(true);
    refusal.invoke(servlet, response.response, checkoutRequest);
    assertEquals(409, response.status);
    return new JSONObject(response.body()).getJSONObject("error");
  }

  private Object prepareOnboardingForPersistedSelection() throws Exception {
    return prepareOnboardingForPersistedSelection((dal, response) -> { });
  }

  /**
   * As {@link #prepareOnboardingForPersistedSelection()}, then hands the still-open DAL helper
   * mock and the response to {@code afterPrepare}, so a spec can verify what was (not) looked up
   * and (not) answered.
   */
  private Object prepareOnboardingForPersistedSelection(
      java.util.function.BiConsumer<MockedStatic<EtendoGoJwtDalHelper>, ResponseCapture>
          afterPrepare) throws Exception {
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"New Productive\",\"currency\":\"EUR\","
            + "\"paymentToken\":\"purchase-1\",\"demoClientId\":\"BODY-DEMO\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":false}}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    ResponseCapture response = mockResponse();
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("user@test.com");
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Method prepare = EtendoGoJwtServlet.class.getDeclaredMethod("prepareOnboarding",
        HttpServletRequest.class, HttpServletResponse.class);
    prepare.setAccessible(true);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<com.etendoerp.go.session.GoLegacyBearer> legacyBearer =
            mockStatic(com.etendoerp.go.session.GoLegacyBearer.class);
        MockedStatic<EmailVerificationDalHelper> verification =
            mockStatic(EmailVerificationDalHelper.class)) {
      legacyBearer.when(com.etendoerp.go.session.GoLegacyBearer::isEnabled).thenReturn(true);
      verification.when(() -> EmailVerificationDalHelper.isEmailVerificationPending(account))
          .thenReturn(false);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com"))
          .thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail("user@test.com"))
          .thenReturn(Set.of("STORED-DEMO"));
      support.when(() -> EtendoGoJwtSupport.findClientIdByName("New Productive"))
          .thenReturn(null);
      Object result = prepare.invoke(servlet, request, response.response);
      assertNotNull("preparation should pass authentication, currency, ownership and paywall", result);
      afterPrepare.accept(dal, response);
      return result;
    }
  }

  private void invokeProfileTransfer(String sourceClientId, String targetClientId)
      throws Exception {
    Method transferProfile = EtendoGoJwtServlet.class.getDeclaredMethod(
        "transferDemoCompanyProfile", String.class, String.class, String.class, String.class);
    transferProfile.setAccessible(true);
    transferProfile.invoke(servlet, "user@test.com", sourceClientId, targetClientId, "ORG-1");
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  @Test
  public void productiveOriginCreatesIndependentPurchaseDespiteOtherOwnedDemos() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("PROD-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("PROD-1")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletCoverageTest::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"DEMO-1\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("PROD-1", "owner@example.test"))
          .thenReturn(true);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
          eq("New Production"), eq("https://app.example.test"), isNull(),
          argThat(EtendoGoJwtServletCoverageTest::selectsNoDemo));
      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
  }

  /** A productive-origin purchase carries no demo source and transfers nothing. */
  private static boolean selectsNoDemo(HostedCheckoutService.SessionOptions options) {
    return options != null && options.getDemoClientId() == null
        && !options.isTransferProducts() && !options.isTransferContacts();
  }

  /**
   * ETP-5548 (product decision): once a demo originated a productive environment, a later
   * purchase from it is a clean productive environment — no source, no data transfer — even when
   * the request still names that demo.
   */
  @Test
  public void purchaseFromAnAlreadyAssociatedDemoCreatesACleanProductive() throws Exception {
    assertCleanProductivePurchaseFromSessionDemo(true, true);
  }

  /**
   * ETP-5743: a demo the account was only invited into is not the buyer's to copy. Buying from
   * inside it creates a clean productive environment, even when the request names it as source.
   */
  @Test
  public void purchaseFromADemoTheAccountIsOnlyInvitedIntoCreatesACleanProductive()
      throws Exception {
    assertCleanProductivePurchaseFromSessionDemo(false, false);
  }

  private void assertCleanProductivePurchaseFromSessionDemo(boolean sessionDemoAssociated,
      boolean sessionDemoOwned) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("SPENT-DEMO");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("SPENT-DEMO")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    purchaseServlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("SPENT-DEMO")).thenReturn(sessionDemoAssociated);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletCoverageTest::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"SPENT-DEMO\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("SPENT-DEMO", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("SPENT-DEMO", "owner@example.test"))
          .thenReturn(sessionDemoOwned);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
          eq("New Production"), eq("https://app.example.test"), isNull(),
          argThat(EtendoGoJwtServletCoverageTest::selectsNoDemo));
      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
  }

  /**
   * ETP-5047 — a canceled productive tenant reads plan "free" (its closed row answers canceled),
   * yet it is not a demo. A purchase from its own session is a clean productive environment, as on
   * develop: no DEMO_SELECTION_REQUIRED, and never itself as the source, even when named.
   */
  @Test
  public void purchaseFromACanceledProductiveCreatesACleanProductiveWithoutASource()
      throws Exception {
    assertCanceledProductiveOriginBuysClean(
        "{\"clientName\":\"New Production\"}");
    assertCanceledProductiveOriginBuysClean(
        "{\"clientName\":\"New Production\",\"demoClientId\":\"CANCELED-PROD\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
  }

  private void assertCanceledProductiveOriginBuysClean(String body) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("CANCELED-PROD");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("CANCELED-PROD")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    purchaseServlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isProductiveEnvironment("CANCELED-PROD")).thenReturn(true);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletCoverageTest::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases", body);
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();
    OBDal dalInstance = mock(OBDal.class);
    when(dalInstance.get(Client.class, "CANCELED-PROD")).thenReturn(mock(Client.class));

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("CANCELED-PROD",
          "owner@example.test")).thenReturn(true);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(response.body(), 201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
    verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
        eq("New Production"), eq("https://app.example.test"), isNull(),
        argThat(EtendoGoJwtServletCoverageTest::selectsNoDemo));
  }

  @Test
  public void billingPurchaseProjectionIncludesCreatedClientIdForSelectorReconciliation()
      throws Exception {
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    Client createdClient = mock(Client.class);
    when(purchase.getRequest()).thenReturn("purchase-123");
    when(purchase.getCheckoutRequestStatus()).thenReturn("PROVISIONED");
    when(purchase.getClientName()).thenReturn("Acme Production");
    when(purchase.getCreatedClient()).thenReturn(createdClient);
    when(createdClient.getId()).thenReturn("created-client-456");

    Method projection = EtendoGoJwtServlet.class.getDeclaredMethod(
        "buildBillingPurchaseJson", CheckoutRequest.class);
    projection.setAccessible(true);
    JSONObject body = (JSONObject) projection.invoke(servlet, purchase);

    assertEquals("created-client-456", body.getString("createdClientId"));
  }

  @Test
  public void freeOriginWithoutDemoSelectionReturnsDemoSelectionRequired() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("FREE-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.tenantPlanService = tenantPlan;
    purchaseServlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(tenantPlan.resolvePlan("FREE-1")).thenReturn(TenantPlanService.PLAN_FREE);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1"))
          .thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("FREE-1", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("FREE-1", "owner@example.test"))
          .thenReturn(true);

      purchaseServlet.doPost(request, response.response);

      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
    }

    assertEquals(400, response.status);
    assertEquals("DEMO_SELECTION_REQUIRED",
        new JSONObject(response.body()).getJSONObject("error").getString("code"));
    org.mockito.Mockito.verify(checkout, never()).createSession(anyString(), anyString(), anyString(),
        anyString(), any(), any(HostedCheckoutService.SessionOptions.class));
  }

  @Test
  public void billingPurchaseRejectsDemoOwnedByAnotherAccount() throws Exception {
    assertInvalidDemoSelectionRejected(false, false, TenantPlanService.PLAN_FREE, false, false);
  }

  /**
   * ETP-5743: an invited member has a user in the owner's demo, but choosing it as the source
   * would copy and lock someone else's environment.
   */
  @Test
  public void billingPurchaseRejectsDemoTheAccountIsOnlyInvitedInto() throws Exception {
    assertInvalidDemoSelectionRejected(true, false, TenantPlanService.PLAN_FREE, false, false);
  }

  @Test
  public void billingPurchaseRejectsOwnedDemoThatIsNoLongerFree() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_PRODUCTIVE, false, false);
  }

  /** ETP-5548: a demo that already originated a productive environment is not a source again. */
  @Test
  public void billingPurchaseRejectsDemoThatAlreadyOriginatedAProductive() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_FREE, true, false);
  }

  /**
   * ETP-5047 — a canceled productive environment reads plan "free" and carries no association
   * marker, but it is not a demo: naming it as the source of a purchase is refused.
   */
  @Test
  public void billingPurchaseRejectsACanceledProductiveAsDemoSource() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_FREE, false, true);
  }

  private void assertInvalidDemoSelectionRejected(boolean selectedDemoMember,
      boolean selectedDemoOwned, String selectedDemoPlan, boolean selectedDemoAssociated,
      boolean selectedDemoProductive) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("FREE-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.tenantPlanService = tenantPlan;
    purchaseServlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(tenantPlan.resolvePlan("FREE-1")).thenReturn(TenantPlanService.PLAN_FREE);
    when(tenantPlan.resolvePlan("DEMO-1")).thenReturn(selectedDemoPlan);
    when(purchaseServlet.tenantEnvironmentLifecycleService.isAssociatedWithProductive("DEMO-1"))
        .thenReturn(selectedDemoAssociated);
    when(purchaseServlet.tenantEnvironmentLifecycleService.isProductiveEnvironment("DEMO-1"))
        .thenReturn(selectedDemoProductive);

    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"DEMO-1\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();
    OBDal dalInstance = mock(OBDal.class);
    when(dalInstance.get(Client.class, "DEMO-1")).thenReturn(mock(Client.class));

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("FREE-1",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("FREE-1",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("DEMO-1",
          "owner@example.test")).thenReturn(selectedDemoMember);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("DEMO-1",
          "owner@example.test")).thenReturn(selectedDemoOwned);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);
    }

    assertEquals(400, response.status);
    assertEquals("INVALID_DEMO_SELECTION",
        new JSONObject(response.body()).getJSONObject("error").getString("code"));
    org.mockito.Mockito.verifyNoInteractions(checkout);
  }

  // ===================== POST /register — error path =====================

  @Test
  public void registerDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/register",
        "{\"email\":\"new@test.com\",\"password\":\"Str0ng!Pass1\",\"name\":\"New User\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("new@test.com"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /login — success path =====================

  @Test
  public void loginValidCredentialsReturnsToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"user@test.com\",\"password\":\"secret\"}");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(eq(account), anyString()));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("success", body.getString("status"));
    assertNotNull(body.getString("token"));
  }

  @Test
  public void loginNoLocalPasswordReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"sso@test.com\",\"password\":\"secret\"}");

    Account account = mock(Account.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("sso@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void loginDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"user@test.com\",\"password\":\"secret\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /sso/google — update / conflict / error =====================

  @Test
  public void ssoGoogleExistingSsoIdentityUpdatesSession() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@gmail.com");
    when(account.getName()).thenReturn("Google User");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenReturn(account);

      ssoServlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.updateSsoSession(
          eq(account), eq("user@gmail.com"), anyString(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("sso", body.getString("authMethod"));
  }

  @Test
  public void ssoGoogleConflictingExistingSsoLinkReturnsConflict() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    Account account = mock(Account.class);
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@gmail.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.linkSsoIdentityIfCompatible(account,
          "google", "google-sub", "user@gmail.com")).thenReturn(false);

      ssoServlet.doPost(req, resp.response);
    }

    assertEquals(409, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("already linked"));
  }

  @Test
  public void ssoGoogleDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenThrow(new RuntimeException("db down"));

      ssoServlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /change-password — validation branches =====================

  @Test
  public void changePasswordMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"b\"}");

    servlet.doPost(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"currentPassword\":\"a\"}")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"\",\"newPassword\":\"\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /password-reset/request — validation branches ===========

  @Test
  public void passwordResetRequestInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestMissingEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestEmptyEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{\"email\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestDatabaseErrorReturnsNeutralSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * ETP-5115 / AUTH-05. This test used to assert the opposite: that an account with no local
   * password got no token and no email. That was the bug, pinned as if it were the contract — every
   * SSO-created account has no local password by design, so the one flow that exists to recover
   * access was a silent no-op for exactly the people who could not get in. It now issues the same
   * token and mails the same link, through the set-password contract rather than reset-password,
   * because the account is being asked to create a first password and not to restore a forgotten
   * one. Rewritten rather than deleted, so the regression cannot come back unnoticed.
   */
  @Test
  public void passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"sso@test.com\"}");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    // The link builder is pinned rather than left to read the ambient app base URL, same reason as
    // registerSuccessCreatesAccount: without it the outcome depends on whether the machine running
    // the suite has etendo.go.app.baseUrl set, and a null link makes the send be skipped entirely.
    // Pinning PublicUrlResolver instead would not work — it would also stub appendPath, which the
    // builder uses, so the link would come back null anyway.
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("sso@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendSetPassword(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendPasswordReset(any(), anyString(), anyString(), any());
    }
    // The response is the same neutral body an unknown address gets. Varying it by account state
    // would tell an anonymous prober both that the address exists and which provider it uses.
    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  // ===================== POST /password-reset/confirm — validation branches ===========

  @Test
  public void passwordResetConfirmInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/confirm");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm", "{\"token\":\"t\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"\",\"password\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"valid-token\",\"password\":\"Str0ng!Pass1\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          anyString(), any(Date.class))).thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /me — database error =====================

  @Test
  public void meDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/me");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /environments — data-mapping loop =====================

  @Test
  public void environmentsMapsUsersWithAndWithoutOrganizations() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    User userWithOrgs = mock(User.class);
    Client clientWithOrgs = mock(Client.class);
    when(clientWithOrgs.getId()).thenReturn("client-1");
    when(userWithOrgs.getClient()).thenReturn(clientWithOrgs);

    User userWithoutOrgs = mock(User.class);
    Client clientWithoutOrgs = mock(Client.class);
    when(clientWithoutOrgs.getId()).thenReturn("client-2");
    when(userWithoutOrgs.getClient()).thenReturn(clientWithoutOrgs);

    Organization org = mock(Organization.class);

    List<User> users = new ArrayList<>();
    users.add(userWithOrgs);
    users.add(userWithoutOrgs);

    // The environment list resolves every tenant's plan through ONE subscription query built
    // before the sort. Stubbed here so this mapping spec stays a pure unit test.
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    when(subscriptionService.findOpenForClients(any())).thenReturn(java.util.Map.of());
    servlet.subscriptionService = subscriptionService;

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(users);
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-1"))
          .thenReturn(Collections.singletonList(org));
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-2"))
          .thenReturn(Collections.emptyList());
      dalMock.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          any(Client.class), any(), any(User.class), any(EnvironmentPlanCache.class)))
          .thenReturn(new JSONObject());

      servlet.doGet(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithOrgs), eq(org), eq(userWithOrgs), any(EnvironmentPlanCache.class)));
      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithoutOrgs), eq(null), eq(userWithoutOrgs),
          any(EnvironmentPlanCache.class)));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals(2, body.getJSONArray("environments").length());
    // The account email is the backend's flag-targeting key, returned so the web client can target
    // on the same identity without a second call to /me (ETP-4686).
    assertEquals("user@test.com", body.getString("accountEmail"));
  }

  @Test
  public void environmentsDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /login (environment login) — success / not found ========

  @Test
  public void envLoginSuccessGeneratesJwt() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    User user = mock(User.class);
    Role role = mock(Role.class);
    EtendoGoJwtSupport.RoleListData roleListData =
        new EtendoGoJwtSupport.RoleListData("role-1", new JSONArray());

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(user);
    when(obDal.get(Role.class, "role-1")).thenReturn(role);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class);
         var swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubAuthenticatedAccount(dalMock);
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "user-1")).thenReturn(true);
      supportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(user, role))
          .thenReturn("jwt-token");

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("jwt-token", body.getString("token"));
    assertNotNull(body.getJSONArray("roleList"));
  }

  /** ETP-5096 — the token is minted for the user's default role, not for the oldest one. */
  @Test
  public void envLoginMintsTheJwtForTheDefaultRoleNotTheOldest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    User user = mock(User.class);
    Role defaultRole = mock(Role.class);
    EtendoGoJwtSupport.RoleListData roleListData =
        new EtendoGoJwtSupport.RoleListData("oldest-role", "default-role", new JSONArray());

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(user);
    when(obDal.get(Role.class, "default-role")).thenReturn(defaultRole);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class);
         var swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubAuthenticatedAccount(dalMock);
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "user-1")).thenReturn(true);
      supportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(user, defaultRole))
          .thenReturn("jwt-default-role");

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("jwt-default-role", new JSONObject(resp.body()).getString("token"));
    verify(obDal, never()).get(Role.class, "oldest-role");
  }

  @Test
  public void envLoginUserNotFoundReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    EtendoGoJwtSupport.RoleListData roleListData =
        new EtendoGoJwtSupport.RoleListData(null, new JSONArray());

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(null);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubAuthenticatedAccount(dalMock);
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "user-1")).thenReturn(true);
      supportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);

      servlet.doGet(req, resp.response);
    }

    assertEquals(404, resp.status);
  }

  @Test
  public void envLoginDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /onboarding — pre-flight branches =====================

  @Test
  public void onboardingTokenValidationDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  @Test
  public void onboardingMissingClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("clientName"));
  }

  @Test
  public void resolveOrCreateClientReturnsTheIdFromTheCreationSession() throws Exception {
    StringWriter stream = new StringWriter();
    PrintWriter writer = new PrintWriter(stream);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    when(vars.getSessionValue("AD_Client_ID")).thenReturn("created-client-id");

    Class<?> requestType = Class.forName(
        "com.etendoerp.go.rest.EtendoGoJwtServlet$OnboardingRequestData");
    Constructor<?> constructor = requestType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object requestData = constructor.newInstance();
    Field clientName = requestType.getDeclaredField("clientName");
    clientName.setAccessible(true);
    clientName.set(requestData, "Acme");
    Field fullName = requestType.getDeclaredField("fullName");
    fullName.setAccessible(true);
    fullName.set(requestData, "Ada Lovelace");

    Method resolver = EtendoGoJwtServlet.class.getDeclaredMethod("resolveOrCreateClient",
        PrintWriter.class, VariablesSecureApp.class, String.class, requestType, String.class,
        String.class, String.class);
    resolver.setAccessible(true);

    OBError success = new OBError();
    success.setType("Success");
    String[] createdUnder = new String[1];

    try (var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var setupMock = mockConstruction(InitialClientSetup.class, (setup, context) ->
             when(setup.createClient(any(VariablesSecureApp.class), anyString(), anyString(),
                 anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(),
                 isNull(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                 .thenAnswer(invocation -> {
                   // InitialClientSetup.insertClient stores the actual created ID in this session.
                   VariablesSecureApp creationVars = invocation.getArgument(0);
                   creationVars.setSessionValue("AD_Client_ID", "created-client-id");
                   createdUnder[0] = invocation.getArgument(2);
                   return success;
                 }))) {
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
          .thenReturn(null, "stale-name-lookup-id");
      supportMock.when(() -> EtendoGoJwtSupport.buildClientUsername("owner@test.com", "Acme"))
          .thenReturn("acme-admin");

      Object result = resolver.invoke(servlet, writer, vars, "owner@test.com", requestData,
          PAID_PROVISIONING_NAME, "currency-1", "temporary-password");

      assertEquals("created-client-id", result);
      supportMock.verify(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME));
      // ETP-5548: the client is created under the provisioning name, never the company name.
      assertEquals(PAID_PROVISIONING_NAME, createdUnder[0]);
    }
  }

  @Test
  public void onboardingEmptyClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{\"clientName\":\"  \"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingUnknownCurrencyReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"XYZ\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("XYZ"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("Unknown currency"));
  }

  @Test
  public void onboardingExistingClientOwnedByAnotherAccountStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      // The attempt's half-built client owned by ANOTHER account -> resume refused (tenant
      // isolation, ETP-4428; looked up by provisioning name since ETP-5548).
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(false);

      servlet.doPost(req, resp.response);
    }

    // NDJSON stream: the servlet sets 200 before streaming, then emits a failure result line.
    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("belongs to another account"));
  }

  @Test
  public void onboardingExistingClientMissingAdminRoleStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      // No admin user-role for the resolved client -> resolveAdminContextData fails.
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Admin role"));
  }

  @Test
  public void onboardingExistingClientResumesUntilDatasetOrgMissing() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    UserRoles adminUserRole = mock(UserRoles.class);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("role-1");
    User contact = mock(User.class);
    when(contact.getId()).thenReturn("user-1");
    when(adminUserRole.getRole()).thenReturn(role);
    when(adminUserRole.getUserContact()).thenReturn(contact);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(adminUserRole);
      supportMock.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1"))
          .thenReturn("star-org");
      // Organization already exists (resume path) but no first org can be resolved afterwards.
      supportMock.when(() -> EtendoGoJwtSupport.organizationExists("client-1"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"step\":\"organization\""));
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Organization not found"));
  }

  // ===================== applySocialName() — ETP-4749 =====================
  //
  // AD_Org.SocialName ("Nombre comercial" in the Organization settings window) was never
  // set anywhere in the onboarding flow — InitialOrgSetup/InitialSetupUtility (Etendo core)
  // only set Name/SearchKey. applySocialName() reuses the same clientName already used for
  // Name (which the wizard's CompanyStep.jsx already resolves to the user's Full Name for
  // Freelancers, since that business type has no separate Company Name field) and persists
  // it once, right after organization creation succeeds — never as part of
  // OnboardingOrgInfoService's idempotent reconcile chain, so a resumed/retried onboarding
  // call never overwrites a "Nombre comercial" the user already edited by hand.

  @Test
  public void applySocialNameSetsSocialNameAndSavesWhenOrganizationFound() {
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertTrue(result);
      verify(org).setSocialName("Acme Corp");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }

  @Test
  public void applySocialNameUsesTheFreelancerFullNameFallbackAlreadyResolvedByTheWizard() {
    // CompanyStep.jsx (schema_forge_core/packages/etendo-go-core) already resolves clientName
    // to the Freelancer's Full Name before this ever reaches Java — applySocialName has no
    // businessType branching of its own, it just persists whatever clientName it is given.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Jane Freelancer");

      assertTrue(result);
      verify(org).setSocialName("Jane Freelancer");
    }
  }

  @Test
  public void applySocialNameReturnsFalseAndDoesNotSaveWhenOrganizationNotFound() {
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertFalse(result);
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  @Test
  public void applySocialNameHasNoBlankGuardUnlikeApplyTaxId() {
    // Unlike OnboardingOrgInfoService.applyTaxId() (a deliberate no-op on blank, because
    // Tax ID is genuinely optional), the clientName reaching this method is guaranteed
    // non-blank by parseOnboardingRequest()'s upstream validation (FIELD_CLIENT_NAME must
    // not be empty — see parseOnboardingRequest's own validation branch). This method
    // intentionally carries no blank guard of its own: a blank value would still be
    // persisted as-is. Locking this in so a future "harmonize with applyTaxId" refactor
    // doesn't silently mask an upstream validation bug behind a no-op here.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "");

      assertTrue(result);
      verify(org).setSocialName("");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }

  // ===================== POST /change-password — ETP-5115 enrolment =====================
  //
  // An account created through an identity provider has passwordHash null by design and could
  // not give itself a local password from inside the app. currentPassword used to be read as a
  // required field while parsing the body, so such a caller — who has nothing to put there — was
  // rejected for missing credentials before anything ever looked at the account, which made the
  // NO_LOCAL_PASSWORD branch unreachable by the very accounts it described. It is now read with
  // optString and whether it is required is decided once the account is known.

  /**
   * ETP-5115. This test previously asserted the opposite — that an account with no local password
   * got 400 NO_LOCAL_PASSWORD. That branch is gone: such an account is enrolling, and the bearer
   * token already proves who is asking, so nothing is verified. Rewritten rather than deleted so
   * the dead end cannot come back unnoticed. Note the old assertion had also stopped proving its
   * own point — its newPassword was "b", which the strength policy rejects before the account is
   * ever looked up, so the 400 it saw no longer came from the branch named in the method.
   */
  @Test
  public void changePasswordWithoutLocalPasswordEnrolsAndMailsPasswordAdded() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-sso");
    when(account.getEmail()).thenReturn("sso@test.com");
    when(account.getName()).thenReturn("SSO User");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals("success", json.getString("status"));
    // The session token handed to the DAL is freshly generated and is the one returned to the
    // caller — enrolling must rotate the session exactly as changing does.
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    // "Your password was changed" is alarming and wrong for somebody who just created a first one.
    verify(emailSender).sendPasswordAdded(account);
    verify(emailSender, never()).sendPasswordChanged(any());
    verify(emailSender, never()).sendPasswordChanged(any(), anyString());
  }

  @Test
  public void changePasswordWithLocalPasswordMailsPasswordChangedAndRotatesSession()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"secret\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    verify(emailSender).sendPasswordChanged(account);
    verify(emailSender, never()).sendPasswordAdded(any());
  }

  /**
   * Making currentPassword optional while parsing must not make it optional in fact: an account
   * that has a password still has to supply one, and still gets the same error code it always did.
   */
  @Test
  public void changePasswordWithLocalPasswordAndNoCurrentPasswordReturnsMissingCredentials()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  @Test
  public void changePasswordWithLocalPasswordAndWrongCurrentPasswordReturnsUnauthorized()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"wrong\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
    assertEquals("INVALID_CURRENT_PASSWORD",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  /**
   * The strength policy still runs before the account is resolved, so a weak newPassword costs no
   * database lookup regardless of whether the caller is enrolling.
   */
  @Test
  public void changePasswordWeakNewPasswordIsRejectedBeforeAnyAccountLookup() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password", "{\"newPassword\":\"weak\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.findActiveAccountByToken(anyString()), never());
    }

    assertEquals(400, resp.status);
  }

  /**
   * The bearer token is what proves identity in the enrolment branch, so an invalid one must still
   * be refused even now that the request carries no currentPassword to reject it on instead.
   */
  @Test
  public void changePasswordInvalidTokenStillUnauthorizedWithoutCurrentPassword()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
  }

  // ===================== POST /password-reset/request — ETP-5115 branch split ==========

  /**
   * The sibling of {@link #passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink}.
   * Widening the gate to let SSO accounts through must not have swapped the copy for everyone else:
   * an account that does have a password is restoring one it forgot, not creating a first.
   */
  @Test
  public void passwordResetRequestKnownEmailWithLocalPasswordIssuesResetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendPasswordReset(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendSetPassword(any(), anyString(), anyString(), any());
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * The anti-enumeration guard, asserted explicitly rather than left implied by three separate
   * tests each checking only its own status. Varying the answer by account state would confirm to
   * an anonymous prober both that an address is registered and which identity provider it uses, so
   * the three branches — no account, reset, enrol — must produce a byte-identical body. The
   * disclosure belongs in the email, which only the owner of the mailbox reads.
   */
  @Test
  public void passwordResetRequestNeutralResponseIsIdenticalAcrossAllThreeBranches()
      throws Exception {
    String unknown = passwordResetRequestBody(null);
    String reset = passwordResetRequestBody(Boolean.TRUE);
    String enrol = passwordResetRequestBody(Boolean.FALSE);

    assertEquals(unknown, reset);
    assertEquals(unknown, enrol);
  }

  /**
   * Drives one password-reset request and returns the raw response body.
   *
   * @param hasLocalPassword null for an address with no account at all, otherwise whether the
   *     account found already has a local password
   * @return the exact bytes written back to the caller
   */
  private static String passwordResetRequestBody(Boolean hasLocalPassword) throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"probe@test.com\"}");

    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    // Hoisted out of the when(...) below on purpose: a helper that stubs a mock cannot be called
    // inline inside a stubbing argument without tripping Mockito's UnfinishedStubbingException.
    final Account account = hasLocalPassword == null ? null : mock(Account.class);
    if (account != null) {
      when(account.getEmail()).thenReturn("probe@test.com");
    }

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("probe@test.com"))
          .thenReturn(account);
      if (account != null) {
        dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account))
            .thenReturn(hasLocalPassword);
      }

      servletWithEmailSender.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    return resp.body();
  }

  // ===================== Helpers =====================

  private static String testPasswordHash(String password) throws Exception {
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(salt);
    byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(salt) + ":"
        + Base64.getEncoder().encodeToString(hash);
  }

  // ===================== applyPaidUpgradeSideEffects — ETP-5046 per-tenant retirement ==========
  //
  // The paid-upgrade path used to write BOTH stores on every upgrade: the ETGO_SUBSCRIPTION row
  // AND the legacy ETGO_TenantPlan preference. Since ETP-5046 the subscription is the source of
  // truth, so writing the preference alongside it was not a safety measure but a second answer
  // that drifts. The preference is now written ONLY when the subscription write failed — which is
  // exactly when TenantPlanPreferenceFallback needs it — and is RETIRED on the success path, so a
  // newly paid tenant lands directly in the post-cutover state. Grep marker for the Phase F
  // deletion: ETP-5046-TRANSITIONAL-FALLBACK.

  private static final String PAID_CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String PAID_STAR_ORG_ID = "9F5511B92BD0465FA678F75278FA9C3A";
  private static final String PAID_TOKEN = "chk_etp5046";

  /**
   * Wires the servlet with mocked collaborators and a checkout request that carries a plan, so the
   * subscription write has everything it needs.
   *
   * @param subscriptionOpens whether {@code openSubscription} succeeds or throws
   * @return the mocked collaborators, for verification
   */
  private PaidUpgradeFixture givenPaidUpgrade(boolean subscriptionOpens) {
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    CheckoutRequest checkoutRequest = mock(CheckoutRequest.class);
    when(checkoutRequest.getPlan()).thenReturn(mock(Plan.class));
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(checkoutRequest);
    if (subscriptionOpens) {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
          any()))
          .thenReturn(mock(Subscription.class));
    } else {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
          any()))
          .thenThrow(new IllegalStateException("subscription write failed"));
    }
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    // The lifecycle projection is a separate concern from the payment record and has its own
    // specs; stubbed to succeed so it contributes no ERROR lines to the assertions below.
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;
    return fixture;
  }

  private void applyPaidUpgrade() {
    // The static DAL helper is mocked so no stray OBDal read can add an ERROR line to the log
    // assertions below. The demo link is no longer made here: paid onboarding links the demo
    // recorded on the purchase, in transferDemoCompanyProfile.
    try (MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.applyPaidUpgradeSideEffects(PAID_CLIENT_ID, PAID_STAR_ORG_ID, "Acme S.L.",
          "user@test.com", PAID_TOKEN);
    }
  }

  @Test
  public void paidUpgradeWithASubscriptionDoesNotWriteThePreferenceAndRetiresTheStaleOne() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID)).thenReturn(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    // The whole point: no parallel truth is written any more.
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    // ...and whatever marker this tenant still carried is retired, so it is immediately in the
    // post-cutover state and the fleet-wide count moves one closer to zero.
    verify(fixture.tenantPlanService).retireProductivePreference(PAID_CLIENT_ID);
    // A recorded productive tenant still gets its ETSG_ForceTestMode override reverted (ETP-5117).
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWhoseSubscriptionWriteFailsWritesThePreferenceAsTheSafetyNet() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    // Without a subscription row, the preference is the ONLY record that this tenant paid, and
    // TenantPlanPreferenceFallback is what will read it back. This is the case the fallback and
    // the whole transitional apparatus exist for.
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    // Nothing is retired: retiring the safety net in the same breath as writing it would leave the
    // tenant with no record at all.
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWithNoCheckoutRequestFallsBackToThePreferenceAndRetiresNothing() {
    // The third way the subscription write can fail to record anything: the token resolves to no
    // request at all, so there is no plan to open a subscription on. Same answer as a throwing
    // write — the safety net is written, nothing is retired.
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(null);
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    // Stubbed to succeed as in givenPaidUpgrade: a failed projection now fails the upgrade closed
    // (ETP-5548), which is its own concern and not what this spec is about.
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    verifyNoInteractions(fixture.subscriptionService);
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
  }

  @Test
  public void aFailedRetirementIsLoggedAndNeverFailsAnUpgradeThatWasAlreadyPaidFor() {
    // A failed retirement is the one failure with nothing contradictory left behind — the
    // subscription records the payment, the transitional fallback simply keeps answering for the
    // tenant and the R37 backfill retires the row later — so unlike a missing payment record
    // (ETP-5548 aborts on that) it must be logged loudly and then ignored.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID))
        .thenThrow(new IllegalStateException("no session"));
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      List<String> logged = errors.messagesAt(Level.ERROR);
      assertEquals("the failure must be searchable, not silent: " + logged, 1, logged.size());
      assertTrue("the line must name the tenant: " + logged.get(0),
          logged.get(0).contains(PAID_CLIENT_ID));
      assertTrue("the line must name the preference that survived: " + logged.get(0),
          logged.get(0).contains(TenantPlanService.PREFERENCE_ATTRIBUTE));
      // The upgrade completed all the same: the subscription was written and the fiscal test-mode
      // override was still reverted.
      verify(fixture.subscriptionService)
          .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
      verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
      // And the failed retirement must NOT make the servlet fall back to writing the marker: the
      // subscription exists, so the tenant is recorded.
      verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    } finally {
      errors.detach();
    }
  }

  @Test
  public void aFallbackPurchaseOpensOnTheGrandfatheredPlanWithTheChargedPrice() {
    // Bought under the legacy price fallback: the request carries legacy-productive, which has no
    // price of its own, and the configured price that was actually charged. The subscription must
    // snapshot THAT price — it is the only record of what this subscriber pays.
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    Plan legacy = mock(Plan.class);
    when(legacy.getSearchKey()).thenReturn("legacy-productive");
    Account payer = mock(Account.class);
    CheckoutRequest checkoutRequest = mock(CheckoutRequest.class);
    when(checkoutRequest.getPlan()).thenReturn(legacy);
    when(checkoutRequest.getEtendoGoAccount()).thenReturn(payer);
    when(checkoutRequest.getStripeCustomer()).thenReturn("cus_legacy");
    when(checkoutRequest.getStripeSubscription()).thenReturn("sub_legacy");
    when(checkoutRequest.getStripePrice()).thenReturn("price_LEGACY_configured");
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(checkoutRequest);
    Subscription opened = mock(Subscription.class);
    when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
        any())).thenReturn(opened);
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;

    applyPaidUpgrade();

    verify(fixture.subscriptionService).openSubscription(PAID_CLIENT_ID, legacy, payer,
        "cus_legacy", "sub_legacy", "price_LEGACY_configured");
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffWritesThePreferenceAndOpensNoRow() {
    // ETGO_SubscriptionModel is not 'Y' in this environment: the purchase is recorded exactly as
    // before ETP-5046 — the ETGO_TenantPlan marker, no ETGO_SUBSCRIPTION row — even though the
    // checkout request carries a plan and the row WOULD open.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    // The only subscription call is the "does this tenant already have a row?" lookup.
    verify(fixture.subscriptionService).hasAnySubscription(PAID_CLIENT_ID);
    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verifyNoInteractions(fixture.checkoutRequestStore);
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    // The marker just written is the tenant's only record: retiring it would erase the payment.
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
    // The rest of the paid upgrade is unchanged: lifecycle projection and the demo override.
    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffIsNotLoggedAsAFailure() {
    // OFF is the default state of every environment until its operator switches, so the
    // preference path it takes must not read like the failed-subscription safety net.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      assertTrue("a switched-off environment is not an error: " + errors.messagesAt(Level.ERROR),
          errors.messagesAt(Level.ERROR).isEmpty());
    } finally {
      errors.detach();
    }
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffStillAbortsWhenThePreferenceCannotBeWritten() {
    // With no row to fall back on, the marker is the payment record: failing to write it must
    // fail the upgrade closed (ETP-5548) exactly as on the subscription path.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(false);

    OBException failure = assertThrows(OBException.class, this::applyPaidUpgrade);

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.lifecycleService, never()).markProductive(anyString());
    // No subscription was attempted, so the failure reads as it did before ETP-5046.
    assertTrue(failure.getMessage(),
        failure.getMessage().contains("could not be marked as plan 'productive'"));
  }

  @Test
  public void aFailedSubscriptionAndAFailedMarkerStillNameBothRecords() {
    // The other fail-closed wording: a row WAS attempted, so the message says neither exists.
    PaidUpgradeFixture fixture = givenPaidUpgrade(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(false);

    OBException failure = assertThrows(OBException.class, this::applyPaidUpgrade);

    assertTrue(failure.getMessage(),
        failure.getMessage().contains("has neither a subscription nor the fallback plan marker"));
  }

  @Test
  public void aTenantThatAlreadyHasARowIsRecordedOnTheRowEvenWithTheModelOff() {
    // Every read answers from a tenant's row whenever it has one (open or closed). Recording its
    // new payment only in the ETGO_TenantPlan marker would be invisible: a canceled or closed row
    // would keep the paying tenant free and blocked. So the switch decides only for rowless
    // tenants — "a tenant with a row is decided by the row" holds for writes too.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    verify(fixture.tenantPlanService).retireProductivePreference(PAID_CLIENT_ID);
  }

  @Test
  public void withTheModelOnTheExistingRowLookupIsNotEvenNeeded() {
    // ON already means "open a row"; the extra lookup is only paid for on a switched-off
    // environment.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(fixture.subscriptionService, never()).hasAnySubscription(anyString());
  }

  @Test
  public void aFailedRowLookupWithTheModelOffFallsBackToTheMarkerAndIsLogged() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID))
        .thenThrow(new IllegalStateException("no session"));
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
      verify(fixture.subscriptionService, never())
          .openSubscription(anyString(), any(), any(), any(), any(), any());
      List<String> logged = errors.messagesAt(Level.ERROR);
      assertEquals("the failed lookup must be searchable: " + logged, 1, logged.size());
      assertTrue(logged.get(0), logged.get(0).contains(PAID_CLIENT_ID));
    } finally {
      errors.detach();
    }
  }

  @Test
  public void theSubscriptionModelSwitchIsReadOnEveryPurchase() {
    // No caching: an operator who flips the switch must not need a restart. The second purchase
    // of the same servlet instance sees the new value.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    SubscriptionModelSwitch modelSwitch = mock(SubscriptionModelSwitch.class);
    when(modelSwitch.isOn()).thenReturn(false, true);
    servlet.subscriptionModelSwitch = modelSwitch;
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();
    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());

    applyPaidUpgrade();
    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(modelSwitch, times(2)).isOn();
  }

  /** The mocked collaborators of one paid-upgrade spec. */
  private static final class PaidUpgradeFixture {
    final CheckoutRequestStore checkoutRequestStore = mock(CheckoutRequestStore.class);
    final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
    final OnboardingForceTestModeService forceTestModeService =
        mock(OnboardingForceTestModeService.class);
    final TenantEnvironmentLifecycleService lifecycleService =
        mock(TenantEnvironmentLifecycleService.class);
  }

  /**
   * Collects the log events of one logger so a spec can assert on them.
   *
   * <p>Log4j2's default configuration is ERROR-only in this build, so the level is pinned before
   * attaching and restored afterwards; without that a WARN would never reach an appender and a
   * spec asserting on one would pass vacuously.
   */
  private static final class LogCapture extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();
    private final String loggerName;
    private final Level previousLevel;

    private LogCapture(String loggerName, Level previousLevel) {
      super("Etp5046ServletCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
          new Property[0]);
      this.loggerName = loggerName;
      this.previousLevel = previousLevel;
    }

    static LogCapture attachTo(Class<?> type, Level level) {
      String name = type.getName();
      Level previous = LogManager.getLogger(name).getLevel();
      Configurator.setLevel(name, level);
      LogCapture appender = new LogCapture(name, previous);
      appender.start();
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(appender);
      return appender;
    }

    void detach() {
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName))
          .removeAppender(this);
      stop();
      Configurator.setLevel(loggerName, previousLevel);
    }

    List<String> messagesAt(Level level) {
      List<String> messages = new ArrayList<>();
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
      return messages;
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  private static Account stubAuthenticatedAccount(
      MockedStatic<EtendoGoJwtDalHelper> dalMock) {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    // ETP-4575 — both lookups are stubbed because both are genuinely reachable after
    // this merge: the epic's newer handlers still call findActiveAccountByBearerToken
    // directly, while resolveAuthenticatedAccountContext (the unified resolver that
    // serves the cookie session AND the legacy bearer fallback) goes through
    // findActiveAccountByToken. Stubbing only one leaves whichever path the test
    // actually exercises returning null, which surfaces as a misleading 401.
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
        .thenReturn(account);
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
        .thenReturn(account);
    // Platform-only endpoints (/checkout/sessions/*, billing) resolve a legacy bearer through
    // findActiveAccountByPlatformToken (ETP-5421).
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken("valid-token"))
        .thenReturn(account);
    return account;
  }

  /**
   * A paid onboarding must abort when its payment cannot be recorded at all: no subscription row
   * (no checkout request behind the token) and no fallback plan marker either.
   */
  @Test
  public void paidUpgradeMustAbortWhenProductivePlanMarkerCannotBeWritten() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(false);
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(lifecycle, never()).markProductive(anyString());
  }

  /** A lifecycle projection failure must also abort before the pooled tenant can commit. */
  @Test
  public void paidUpgradeMustAbortWhenLifecycleProjectionCannotBeWritten() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(true);
    when(lifecycle.markProductive("CLIENT-PAID")).thenReturn(false);
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(lifecycle).markProductive("CLIENT-PAID");
  }

  /** A failed demo override removal must abort the paid transaction as well. */
  @Test
  public void paidUpgradeMustAbortWhenDemoOverrideCannotBeRemoved() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    OnboardingForceTestModeService forceTestMode = mock(OnboardingForceTestModeService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(true);
    when(lifecycle.markProductive("CLIENT-PAID")).thenReturn(true);
    doThrow(new OBException("override removal failed"))
        .when(forceTestMode).revertTestModeForProductiveTenant("CLIENT-PAID");
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
    servlet.onboardingForceTestModeService = forceTestMode;

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(forceTestMode).revertTestModeForProductiveTenant("CLIENT-PAID");
  }

  @Test
  public void paidPoolBooleanFailureAlertsErrorOnlyAfterConfirmedRollback() throws Exception {
    assertProvisioningAlertOutcome(true, false, true, false, false);
  }

  @Test
  public void failedRollbackSuppressesAlertAndItsIndependentCommit() throws Exception {
    assertProvisioningAlertOutcome(true, false, false, false, false);
  }

  @Test
  public void alertDeliveryFailureDoesNotChangePaidProvisioningFailure() throws Exception {
    assertProvisioningAlertOutcome(true, false, true, true, false);
  }

  @Test
  public void paidPoolCommitAlertsOkAndStillClosesCheckoutWhenDeliveryThrows() throws Exception {
    assertProvisioningAlertOutcome(true, true, true, true, false);
  }

  @Test
  public void committedProvisioningFollowupFailureNeverEmitsFalseErrorAlert() throws Exception {
    assertProvisioningAlertOutcome(true, true, true, false, true);
  }

  @Test
  public void failedRollbackAfterCommitAlsoSuppressesDirtyDiagnosticCommit() throws Exception {
    assertProvisioningAlertOutcome(true, true, false, false, true);
  }

  @Test
  public void classicBooleanFailureAlsoProducesOneSettledErrorAlert() throws Exception {
    assertProvisioningAlertOutcome(false, false, true, false, false);
  }

  // ===================== applyCheckoutPaid — nullable Stripe ids (ETP-5047) ================

  /**
   * A payment-mode checkout reports {@code subscription} (and may report {@code customer}) as
   * JSON {@code null}. Jettison's {@code optString} turns that marker into the text "null", which
   * used to be stored as the Stripe subscription id — and the post-onboarding period read then
   * asked Stripe for {@code /v1/subscriptions/null}. A JSON null must reach the store as blank.
   */
  @Test
  public void aCheckoutPaidWithNullProviderIdsRecordsThemBlankNeverAsTheWordNull()
      throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    servlet.billingEventStore = mock(com.etendoerp.go.payment.BillingEventStore.class);
    when(store.recordPaid(anyString(), anyString(), anyString())).thenReturn(true);
    JSONObject event = new JSONObject("{\"type\":\"checkout.session.completed\",\"data\":"
        + "{\"object\":{\"id\":\"cs_1\",\"mode\":\"payment\",\"customer\":null,"
        + "\"subscription\":null,\"metadata\":{\"request_id\":\"purchase-1\","
        + "\"account_email\":\"user@test.com\"}}}}");
    Method applyCheckoutPaid = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid",
        String.class, String.class, JSONObject.class);
    applyCheckoutPaid.setAccessible(true);

    applyCheckoutPaid.invoke(servlet, "evt_1", "checkout.session.completed", event);

    verify(store).recordPaid("purchase-1", "", "");
  }

  // ===================== applyCheckoutPaid — a paid re-subscription (ETP-5742) ===============
  //
  // A re-subscription names its canceled tenant on the request, so the payment webhook applies
  // the purchase to that tenant itself — the same records a paid upgrade writes — and closes the
  // request, instead of leaving it PAID for an onboarding that would provision a second tenant.

  private static final long REACTIVATION_PAID_EPOCH = 1_790_000_000L;

  private PaidUpgradeFixture givenPaidReactivation() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    Client tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(PAID_CLIENT_ID);
    CheckoutRequest reactivation = mock(CheckoutRequest.class);
    when(reactivation.getCreatedClient()).thenReturn(tenant);
    when(reactivation.getRequest()).thenReturn(PAID_TOKEN);
    when(reactivation.getClientName()).thenReturn("Acme S.L.");
    when(reactivation.getAccountEmail()).thenReturn("user@test.com");
    when(fixture.checkoutRequestStore.recordPaid(anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(reactivation);
    servlet.billingEventStore = mock(com.etendoerp.go.payment.BillingEventStore.class);
    return fixture;
  }

  private void payReactivation() throws Exception {
    JSONObject event = new JSONObject("{\"type\":\"checkout.session.completed\",\"created\":"
        + REACTIVATION_PAID_EPOCH + ",\"data\":{\"object\":{\"id\":\"cs_1\","
        + "\"customer\":\"cus_1\",\"subscription\":\"sub_new\",\"metadata\":{\"request_id\":\""
        + PAID_TOKEN + "\",\"account_email\":\"user@test.com\"}}}}");
    Method applyCheckoutPaid = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid",
        String.class, String.class, JSONObject.class);
    applyCheckoutPaid.setAccessible(true);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      applyCheckoutPaid.invoke(servlet, "evt_1", "checkout.session.completed", event);
    }
  }

  @Test
  public void aPaidReactivationOfARowTenantOpensANewRowAndClosesTheRequest() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(true);

    payReactivation();

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(fixture.subscriptionService,
        fixture.checkoutRequestStore, servlet.billingEventStore);
    order.verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    // Closing the request commits the payment record with it, so it comes after.
    order.verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    order.verify(servlet.billingEventStore).markApplied("evt_1");
    // A row tenant keeps its watermark on the row: the preference one is never written.
    verify(fixture.lifecycleService, never()).recordSubscriptionEventAt(anyString(), any());
    // The new row's period is read from the provider, as after an onboarding.
    verify(fixture.subscriptionService).fillPeriodFromProviderAfterCommit(PAID_CLIENT_ID);
  }

  @Test
  public void aPaidReactivationOfARowlessTenantWritesThePreferenceProjection() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    payReactivation();

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    // The watermark moves to this payment, so a late event of the canceled subscription is stale.
    verify(fixture.lifecycleService).recordSubscriptionEventAt(PAID_CLIENT_ID,
        java.time.Instant.ofEpochSecond(REACTIVATION_PAID_EPOCH));
    verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    verify(servlet.billingEventStore).markApplied("evt_1");
  }

  @Test
  public void aReactivationThatCannotBeRecordedLeavesTheRequestPaidForTheRetry() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.lifecycleService.markProductive(PAID_CLIENT_ID)).thenReturn(false);

    InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
        this::payReactivation);

    // The webhook handler turns this into a FAILED event and a 500, so the provider redelivers.
    assertTrue(thrown.getCause() instanceof OBException);
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(servlet.billingEventStore, never()).markApplied(anyString());
  }

  @Test
  public void aPaidNewPurchaseIsLeftToOnboarding() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(null);

    payReactivation();

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(servlet.billingEventStore).markApplied("evt_1");
  }

  // ===================== PaidReactivationService over the webhook path (ETP-5048) =============
  //
  // The billing reconciliation applies a re-subscription whose payment webhook was lost through
  // applyReactivationIfPending, the webhook's own method, with the provider instant it read.

  private boolean applyThroughTheReconciliation(java.time.Instant paidAt, OBContext caller) {
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(caller);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      boolean applied = new PaidReactivationService(() -> servlet).applyIfPending(PAID_TOKEN,
          paidAt);
      if (applied) {
        // Every context switch on the way was undone: the last context installed is the caller's.
        ArgumentCaptor<OBContext> restored = ArgumentCaptor.forClass(OBContext.class);
        context.verify(() -> OBContext.setOBContext(restored.capture()),
            org.mockito.Mockito.atLeastOnce());
        assertSame(caller, restored.getValue());
      }
      return applied;
    }
  }

  @Test
  public void theReconciliationAppliesALostReactivationThroughTheWebhookPath() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    java.time.Instant paidAt = java.time.Instant.ofEpochSecond(REACTIVATION_PAID_EPOCH + 60);

    assertTrue(applyThroughTheReconciliation(paidAt, mock(OBContext.class)));

    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    // The preference watermark moves to the provider instant the reconciliation read.
    verify(fixture.lifecycleService).recordSubscriptionEventAt(PAID_CLIENT_ID, paidAt);
    verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    verify(fixture.subscriptionService).fillPeriodFromProviderAfterCommit(PAID_CLIENT_ID);
  }

  @Test
  public void theReconciliationFindsNothingToApplyOnceTheWebhookApplied() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(null);

    assertFalse(applyThroughTheReconciliation(null, mock(OBContext.class)));

    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(fixture.lifecycleService, never()).markProductive(anyString());
  }

  @Test
  public void aReactivationTheReconciliationCannotRecordFailsItsCaller() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.lifecycleService.markProductive(PAID_CLIENT_ID)).thenReturn(false);
    OBContext caller = mock(OBContext.class);

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(caller);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      assertThrows(OBException.class, () -> new PaidReactivationService(() -> servlet)
          .applyIfPending(PAID_TOKEN, null));
      ArgumentCaptor<OBContext> restored = ArgumentCaptor.forClass(OBContext.class);
      context.verify(() -> OBContext.setOBContext(restored.capture()),
          org.mockito.Mockito.atLeastOnce());
      assertSame("A failure still hands the caller its context back", caller,
          restored.getValue());
    }
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
  }

  // ===================== fillSubscriptionPeriodBestEffort — the post-commit period read ========
  //
  // A paid onboarding opens the subscription row without a billing period: the purchase's first
  // lifecycle webhooks arrived before the row existed and were ignored for good. So, after the
  // onboarding commit, the servlet asks SubscriptionService to read the period from the provider,
  // in its own transaction, and nothing that step does may fail a tenant that is already
  // committed and paid for.

  private static final String PERIOD_STEP = "subscription billing period";

  @Test
  public void aFilledPeriodIsCommittedInItsOwnTransaction() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(true);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq(PERIOD_STEP), any()));
      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(anyString(), any(), any()),
          never());
    }
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
  }

  @Test
  public void aFillThatWritesNothingCommitsNothing() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(false);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verifyNoInteractions();
    }
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
  }

  @Test
  public void aFailingFillIsRolledBackAndNeverEscapes() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    IllegalStateException failure = new IllegalStateException("no session");
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenThrow(failure);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(anyString(), any()), never());
      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(PERIOD_STEP),
          eq(failure), any()));
    }
  }

  @Test
  public void aFailingPeriodCommitIsRolledBackAndNeverEscapes() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(true);
    servlet.subscriptionService = subscriptions;
    IllegalStateException failure = new IllegalStateException("commit refused");

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(eq(PERIOD_STEP), any()))
          .thenThrow(failure);

      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(PERIOD_STEP),
          eq(failure), any()));
    }
  }

  /**
   * The step runs after the checkout is closed and before the costing schedule is activated:
   * after the onboarding commit, so the row it reads exists; after {@code recordProvisioned}, so a
   * slow provider call never widens the window in which the checkout is not {@code PROVISIONED}
   * and names no created client — a crash there lets a retry provision a second tenant for the
   * same payment.
   */
  @Test
  public void aPaidOnboardingFillsThePeriodAfterClosingTheCheckout() throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      return true;
    });

    String body = runCommittedPaidOnboarding(true, subscriptions, steps);

    assertEquals(List.of("commit:onboarding", "recordProvisioned", "fill",
        "commit:" + PERIOD_STEP, "activateSchedule"), steps);
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
    assertTrue(body, body.contains("\"success\":true"));
  }

  /** The tenant is committed and paid for: a failing period read costs the period, nothing else. */
  @Test
  public void aPaidOnboardingStillSucceedsWhenThePeriodFillThrows() throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      throw new IllegalStateException("provider read exploded");
    });

    String body = runCommittedPaidOnboarding(true, subscriptions, steps);

    assertEquals(List.of("commit:onboarding", "recordProvisioned", "fill",
        "rollback:" + PERIOD_STEP, "activateSchedule"), steps);
    assertTrue(body, body.contains("\"success\":true"));
    assertEquals("The client keeps exactly one result line", 1, countResultLines(body));
  }

  // ===================== clearOnboardingDraftBestEffort — the account is a client-0 row ======

  private static final String DRAFT_CLEAR_STEP = "onboarding draft clear";

  /**
   * The draft lives on {@code ETGO_ACCOUNT}, a client-0 row, and the clear runs after the
   * onboarding commit with the thread still in the new tenant's context. The DAL write check
   * refuses a client-0 row from there at flush, so the clear must run as system — and hand the
   * tenant's context back afterwards.
   */
  @Test
  public void theDraftClearWritesTheClientZeroAccountAsSystem() throws Exception {
    Account account = mock(Account.class);
    OBContext tenant = mock(OBContext.class);
    List<String> steps = new ArrayList<>();
    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(tenant);
      context.when(() -> OBContext.setOBContext("0", "0", "0", "0"))
          .thenAnswer(invocation -> steps.add("system"));
      context.when(() -> OBContext.setOBContext(tenant))
          .thenAnswer(invocation -> steps.add("tenant"));
      dal.when(() -> EtendoGoJwtDalHelper.updateOnboardingDraft(account, null))
          .thenAnswer(invocation -> steps.add("clear"));

      invokeClearOnboardingDraft(account);

      assertEquals(List.of("system", "clear", "tenant"), steps);
      transactions.verifyNoInteractions();
    }
  }

  /**
   * A refused clear must not leave the dirty account in the session: the next commit on the
   * thread — the period fill, the ready email — would flush it again, in whatever context it runs.
   */
  @Test
  public void aFailedDraftClearRollsBackItsOwnWork() throws Exception {
    Account account = mock(Account.class);
    OBSecurityException refused = new OBSecurityException("Client (0) is not present");
    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.updateOnboardingDraft(account, null)).thenThrow(refused);

      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      invokeClearOnboardingDraft(account);

      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(DRAFT_CLEAR_STEP),
          eq(refused), any()));
    }
  }

  private void invokeClearOnboardingDraft(Account account) throws Exception {
    Method clear = EtendoGoJwtServlet.class.getDeclaredMethod("clearOnboardingDraftBestEffort",
        Account.class);
    clear.setAccessible(true);
    clear.invoke(servlet, account);
  }

  /**
   * The classic path (no pooled tenant: here a half-built client this purchase left behind, which
   * the attempt resumes) commits the tenant the same way, so it must fill the period at the same
   * point: after the checkout is closed, before the costing schedule.
   */
  @Test
  public void aClassicPaidOnboardingAlsoFillsThePeriodAfterClosingTheCheckout()
      throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      return true;
    });

    String body = runCommittedPaidOnboarding(false, subscriptions, steps);

    assertEquals(List.of("dataset", "commit:onboarding", "recordProvisioned", "fill",
        "commit:" + PERIOD_STEP, "activateSchedule"), steps);
    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
    assertTrue(body, body.contains("\"success\":true"));
  }

  /**
   * Drives a paid onboarding to a successful commit, recording, in order, every DAL commit and
   * rollback by operation name, the closing of the checkout request and the costing schedule.
   *
   * <p>Pooled: the pool hands out {@code client-1}. Classic: {@code client-1} is the half-built
   * client this purchase left under its provisioning name, resumed with its organization in
   * place; the provisioning chain's dataset pass is stubbed (recorded as {@code dataset}) and the
   * requested-name step finds no client to rename.
   *
   * @param pooled which path the tenant takes
   * @param subscriptions the subscription service the servlet uses
   * @param steps where the steps are recorded; the caller's own stubs may add to it
   * @return the NDJSON body written to the client
   */
  private String runCommittedPaidOnboarding(boolean pooled, SubscriptionService subscriptions,
      List<String> steps) throws Exception {
    if (!pooled) {
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
           MockedConstruction<OnboardingProvisioningChain> chains = mockConstruction(
               OnboardingProvisioningChain.class, withSettings().defaultAnswer(CALLS_REAL_METHODS),
               (chain, context) -> doAnswer(invocation -> steps.add("dataset")).when(chain)
                   .ensureOnboardingDataset(any(), anyString(), anyString(), anyString(),
                       anyString(), any()))) {
        obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
        return runCommittedPaidOnboardingOnPath(false, subscriptions, steps);
      }
    }
    return runCommittedPaidOnboardingOnPath(true, subscriptions, steps);
  }

  private String runCommittedPaidOnboardingOnPath(boolean pooled,
      SubscriptionService subscriptions, List<String> steps) throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(pooled);
    doAnswer(invocation -> steps.add("recordProvisioned")).when(stage.store)
        .recordProvisioned("purchase-1", "client-1", 1L);
    servlet.subscriptionService = subscriptions;
    when(servlet.tenantPlanService.markProductive("client-1", "star-org")).thenReturn(true);
    when(servlet.tenantEnvironmentLifecycleService.markProductive("client-1")).thenReturn(true);
    servlet.onboardingWarehouseAddressService =
        mock(com.etendoerp.go.onboarding.OnboardingWarehouseAddressService.class);
    servlet.onboardingCostingScheduleService =
        mock(com.etendoerp.go.onboarding.OnboardingCostingScheduleService.class);
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    doAnswer(invocation -> steps.add("activateSchedule"))
        .when(servlet.onboardingCostingScheduleService).activateSchedule("client-1");
    runStagedPaidOnboarding(stage, (support, dal, transactions) -> {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      if (!pooled) {
        support.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
            .thenReturn("client-1");
        support.when(() -> EtendoGoJwtSupport.organizationExists("client-1")).thenReturn(true);
      }
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(anyString(), any()))
          .thenAnswer(invocation -> steps.add("commit:" + invocation.getArgument(0)));
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChanges(anyString(), any(), any()))
          .thenAnswer(invocation -> steps.add("rollback:" + invocation.getArgument(0)));
    }, (support, dal, transactions) -> { });
    return stage.response.body();
  }

  /**
   * ETP-5548: the productive marker is the last write before the commit. A step that fails after
   * the tenant exists (here the pooled org-info wiring) must leave no plan, lifecycle or override
   * write behind, and the client must receive exactly one result line: the step's own.
   */
  @Test
  public void paidStepFailureNeverWritesProductiveMetadataAndKeepsItsOwnResult() throws Exception {
    PaidOnboardingRun run = runPaidOnboarding(true, false);

    verify(servlet.tenantPlanService, never()).markProductive(anyString(), anyString());
    verify(servlet.tenantEnvironmentLifecycleService, never()).markProductive(anyString());
    verify(servlet.onboardingForceTestModeService, never())
        .revertTestModeForProductiveTenant(anyString());
    assertEquals("The client keeps the last result line, so there must be only one", 1,
        countResultLines(run.response.body()));
    assertTrue(run.response.body().contains("Org info unavailable"));
    verify(run.store).recordFailureReason("purchase-1",
        ProvisioningFailureReason.CODE_PROVISIONING_FAILED + ": Org info unavailable");
  }

  /**
   * ETP-5548: a company name the account already uses for a productive environment reaches the
   * client once, coded, and is recorded as such. The same name on another account's environment
   * is not a collision.
   */
  @Test
  public void paidNameCollisionIsRecordedWithItsNonRetryableCode() throws Exception {
    PaidOnboardingRun run = runPaidOnboarding(false, true);

    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());

    assertEquals(1, countResultLines(run.response.body()));
    assertTrue(run.response.body().contains(
        "\"code\":\"" + ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE + "\""));
    verify(run.store).recordFailureReason(eq("purchase-1"),
        org.mockito.ArgumentMatchers.startsWith(
            ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE + ": "));
    verify(servlet.tenantPlanService, never()).markProductive(anyString(), anyString());
  }

  /** ETP-5548: the polling contract exposes a code and a fixed text, never the stored cause. */
  @Test
  public void checkoutStatusNeverReturnsTheRawFailureCause() throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = mockRequest("/checkout/sessions/purchase-1");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getCheckoutRequestStatus()).thenReturn("PROVISIONING");
    when(purchase.getClientName()).thenReturn("Acme");
    when(purchase.getFailureReason()).thenReturn(
        "CLIENT_NAME_IN_USE: ERROR duplicate key ad_client_name client 5775CB18");
    when(purchase.getUpdated()).thenReturn(new Date(1_790_000_000_000L));
    when(store.find("purchase-1", "account-1", "user@test.com")).thenReturn(purchase);
    when(store.deriveProvisioningStatus(any())).thenCallRealMethod();
    when(store.isProvisioningRetryAllowed(any())).thenCallRealMethod();

    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account account = stubAuthenticatedAccount(dal);
      when(account.getId()).thenReturn("account-1");
      servlet.doGet(request, response.response);
    }

    JSONObject body = new JSONObject(response.body());
    assertEquals("provisioning_failed", body.getString("status"));
    assertEquals(ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE,
        body.getString("failureCode"));
    assertFalse("A deterministic failure is not offered for retry",
        body.getBoolean("retryAllowed"));
    assertFalse(body.getString("failureReason").contains("duplicate key"));
    assertFalse(body.getString("failureReason").contains("5775CB18"));
    assertEquals(new Date(1_790_000_000_000L).toInstant().toString(),
        body.getString("updatedAt"));
  }

  /** ETP-5548: only a tenant created by the unfinished purchase is hidden from /environments. */
  @Test
  public void unfinishedPaidEnvironmentIsHiddenOnlyWhenCreatedByThePurchase() {
    Date paidAt = new Date(1_790_000_000_000L);
    java.util.Map<String, Date> unfinished = java.util.Map.of("acme", paidAt);
    Client createdAfterPayment = mock(Client.class);
    when(createdAfterPayment.getName()).thenReturn("ACME");
    when(createdAfterPayment.getCreationDate()).thenReturn(new Date(paidAt.getTime() + 1000L));
    Client demoConvertedFromBefore = mock(Client.class);
    when(demoConvertedFromBefore.getName()).thenReturn("Acme");
    when(demoConvertedFromBefore.getCreationDate()).thenReturn(new Date(paidAt.getTime() - 1000L));
    Client unrelated = mock(Client.class);
    when(unrelated.getName()).thenReturn("Other");
    when(unrelated.getCreationDate()).thenReturn(new Date(paidAt.getTime() + 1000L));

    assertTrue(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(createdAfterPayment, unfinished));
    assertFalse("A tenant older than the payment is the customer's working environment",
        EtendoGoJwtServlet.isUnfinishedPaidEnvironment(demoConvertedFromBefore, unfinished));
    assertFalse(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(unrelated, unfinished));
    assertFalse(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(createdAfterPayment,
        java.util.Map.of()));
  }

  /** ETP-5548: a client still under its provisioning name is hidden whatever the purchases say. */
  @Test
  public void clientUnderItsProvisioningNameIsAlwaysHidden() {
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(PAID_PROVISIONING_NAME);

    assertTrue(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(building, java.util.Map.of()));
  }

  /**
   * ETP-5548: the classic path renames its client from the provisioning name to the company name
   * at the end, with every derived name; a client that does not carry it is left alone.
   */
  @Test
  public void requestedClientNameReplacesTheProvisioningNameOnlyOnItsOwnClient() {
    OBDal obDal = mock(OBDal.class);
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(PAID_PROVISIONING_NAME);
    Client finished = mock(Client.class);
    when(finished.getName()).thenReturn("Acme");
    when(obDal.get(Client.class, "client-1")).thenReturn(building);
    when(obDal.get(Client.class, "client-2")).thenReturn(finished);
    Organization org = mock(Organization.class);
    when(org.getName()).thenReturn("Acme");
    when(obDal.get(Organization.class, "org-1")).thenReturn(org);
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);

    try (var context = mockStatic(OBContext.class);
         var dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.applyRequestedClientName("client-1", "org-1", PAID_PROVISIONING_NAME, "Acme");
      servlet.applyRequestedClientName("client-2", "org-2", OTHER_PROVISIONING_NAME, "Acme");
    }

    verify(building).setName("Acme");
    verify(building).setSearchKey("Acme");
    verify(org, never()).setName(anyString());
    verify(servlet.pooledTenantClaimService).renamePlaceholderDerivedNames("client-1",
        PAID_PROVISIONING_NAME, "Acme");
    verify(finished, never()).setName(anyString());
    verify(servlet.pooledTenantClaimService, never()).renamePlaceholderDerivedNames(eq("client-2"),
        anyString(), anyString());
  }

  /**
   * ETP-5548: a free retry may resume the attempt under a different company name. The organization
   * created by the first attempt takes the new name; its legal name follows only while it still
   * equals the old name, so a legal name copied from the demo is kept.
   */
  @Test
  public void resumedOrganizationTakesTheRetriedNameButKeepsACustomLegalName() {
    OBDal obDal = mock(OBDal.class);
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(FREE_PROVISIONING_NAME);
    when(obDal.get(Client.class, "client-1")).thenReturn(building);
    when(obDal.get(Client.class, "client-2")).thenReturn(building);
    Organization defaulted = mock(Organization.class);
    when(defaulted.getName()).thenReturn("First Try SL");
    when(defaulted.getSocialName()).thenReturn("First Try SL");
    Organization customized = mock(Organization.class);
    when(customized.getName()).thenReturn("First Try SL");
    when(customized.getSocialName()).thenReturn("Acme Holdings SA");
    when(obDal.get(Organization.class, "org-1")).thenReturn(defaulted);
    when(obDal.get(Organization.class, "org-2")).thenReturn(customized);
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);

    try (var context = mockStatic(OBContext.class);
         var dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.applyRequestedClientName("client-1", "org-1", FREE_PROVISIONING_NAME, "Acme");
      servlet.applyRequestedClientName("client-2", "org-2", FREE_PROVISIONING_NAME, "Acme");
    }

    verify(defaulted).setName("Acme");
    verify(defaulted).setSearchKey("Acme");
    verify(defaulted).setSocialName("Acme");
    verify(customized).setName("Acme");
    verify(customized, never()).setSocialName(anyString());
  }

  /** ETP-5548: one account never gets two productive environments with the same name. */
  @Test
  public void purchaseNamedLikeAnOwnProductiveIsRefusedBeforeCheckout() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed("Acme", TenantPlanService.PLAN_PRODUCTIVE);

    assertEquals(409, attempt.response.status);
    assertTrue(attempt.response.body().contains(ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE));
    verifyNoInteractions(attempt.checkout);
  }

  /**
   * ETP-5548: the name of the account's demo, or of another account's environment, is free to
   * reuse — the purchase creates a new productive and never converts the demo.
   */
  @Test
  public void purchaseNamedLikeTheDemoOrAnotherAccountReachesCheckout() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed("Acme", TenantPlanService.PLAN_FREE);

    assertEquals(201, attempt.response.status);
    verify(attempt.checkout).createSession(eq("account-1"), eq("owner@example.test"), eq("Acme"),
        eq("https://app.example.test"), isNull(),
        argThat(EtendoGoJwtServletCoverageTest::selectsNoDemo));
  }

  @Test
  public void purchaseWithAReservedProvisioningNameIsRejected() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed(
        "PEND-0123456789ABCDEF0123456789ABCDEF", TenantPlanService.PLAN_FREE);

    assertEquals(400, attempt.response.status);
    verifyNoInteractions(attempt.checkout);
  }

  @Test
  public void onboardingWithAReservedProvisioningNameIsRejected() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\" pend-0123456789abcdef0123456789abcdef \",\"currency\":\"EUR\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(resp.body().contains("reserved"));
  }

  // --- Scoped system context of the ETP-5548 checks (ETP-5046) -----------------------------------
  //
  // Driven directly, never through the checkout endpoints: there hasOwnedEnvironment installs a
  // system context first, which would hide a leak from these two methods.

  private static final String NAME_CHECK_WARNING =
      "Could not check whether company name is in use before checkout";

  /**
   * The name check runs as System with admin mode on, and the caller gets back the very context
   * it had and its admin-mode depth — not a leftover System context.
   */
  @Test
  public void productiveNameCheckRunsAsSystemAndHandsTheCallerItsContextBack() throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OWN-PROD")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    AtomicBoolean lookupRanAsSystem = new AtomicBoolean();

    try (ContextThread thread = new ContextThread(caller, 1);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenAnswer(call -> {
        lookupRanAsSystem.set(thread.isSystemWithAdminMode());
        return List.of("OWN-PROD");
      });
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);

      assertTrue(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertTrue("the lookup must see user/role/client/org 0 with admin mode on",
          lookupRanAsSystem.get());
      assertSame(caller, thread.current.get());
      assertEquals(1, thread.adminDepth.get());
    }
  }

  /** A caller with no context (the webhook's case) gets no context back, not a System one. */
  @Test
  public void productiveNameCheckRestoresNoContextAsNoContext() throws Exception {
    servlet.tenantPlanService = mock(TenantPlanService.class);

    try (ContextThread thread = new ContextThread(null, 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenReturn(List.of());

      assertFalse(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertNull(thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  /**
   * ETP-5548 semantics are unchanged by the scoping: another account's productive and this
   * account's demo do not count; only this account's productive does.
   */
  @Test
  public void productiveNameCheckCountsOnlyThisAccountsProductive() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OTHER-ACCOUNT")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OWN-DEMO")).thenReturn(TenantPlanService.PLAN_FREE);
    when(tenantPlan.resolvePlan("OWN-PROD")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    try (ContextThread thread = new ContextThread(mock(OBContext.class), 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-DEMO",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Other"))
          .thenReturn(List.of("OTHER-ACCOUNT"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Demo"))
          .thenReturn(List.of("OWN-DEMO"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
          .thenReturn(List.of("OTHER-ACCOUNT", "OWN-DEMO", "OWN-PROD"));

      assertFalse("another account's productive is free to reuse",
          isProductiveNameTakenByAccount("Other", "owner@example.test"));
      assertFalse("this account's demo is free to reuse",
          isProductiveNameTakenByAccount("Demo", "owner@example.test"));
      assertTrue("this account's productive is taken",
          isProductiveNameTakenByAccount("Acme", "owner@example.test"));
    }
  }

  /**
   * ETP-5742 — a canceled productive environment reads plan "free" (its closed row) but keeps its
   * name: buying a new environment under it would be refused by the company-name constraint after
   * the payment. Its lifecycle type still says productive, and that counts.
   */
  @Test
  public void productiveNameCheckCountsACanceledProductiveWhosePlanReadsFree() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan(anyString())).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    TenantEnvironmentLifecycleService.EnvironmentSnapshot canceled =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(canceled.getType()).thenReturn(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE);
    when(lifecycle.resolve("OWN-CANCELED")).thenReturn(canceled);

    try (ContextThread thread = new ContextThread(mock(OBContext.class), 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(anyString(),
          eq("owner@example.test"))).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Canceled"))
          .thenReturn(List.of("OWN-CANCELED"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Unknown"))
          .thenReturn(List.of("OWN-UNREADABLE"));

      assertTrue("a canceled productive keeps its name",
          isProductiveNameTakenByAccount("Canceled", "owner@example.test"));
      // An unreadable lifecycle (resolve answers null) does not take a name: the check fails open.
      assertFalse("an unreadable lifecycle is not a productive environment",
          isProductiveNameTakenByAccount("Unknown", "owner@example.test"));
    }
  }

  /** The name lookup failing fails open, logged, and still hands the caller its context back. */
  @Test
  public void productiveNameCheckFailsOpenWhenTheNameLookupThrows() throws Exception {
    servlet.tenantPlanService = mock(TenantPlanService.class);
    assertNameCheckFailsOpen(support -> support.when(
        () -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
        .thenThrow(new IllegalStateException("lookup failed")));
  }

  /** The plan resolution failing fails open the same way. */
  @Test
  public void productiveNameCheckFailsOpenWhenThePlanResolutionThrows() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OWN-PROD")).thenThrow(new IllegalStateException("plan failed"));
    assertNameCheckFailsOpen(support -> support.when(
        () -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenReturn(List.of("OWN-PROD")));
  }

  private void assertNameCheckFailsOpen(
      java.util.function.Consumer<MockedStatic<EtendoGoJwtSupport>> failure) throws Exception {
    OBContext caller = mock(OBContext.class);
    LogCapture warnings = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.WARN);
    try (ContextThread thread = new ContextThread(caller, 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);
      failure.accept(support);

      assertFalse(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
      assertTrue(warnings.messagesAt(Level.WARN).stream()
          .anyMatch(message -> message.startsWith(NAME_CHECK_WARNING)));
    } finally {
      warnings.detach();
    }
  }

  /** The associated-demo check answers the lifecycle service, as System, and restores the caller. */
  @Test
  public void associatedDemoCheckAnswersTheLifecycleAndHandsTheCallerItsContextBack()
      throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("DEMO-FREE")).thenReturn(false);

    try (ContextThread thread = new ContextThread(caller, 0)) {
      AtomicBoolean ranAsSystem = new AtomicBoolean();
      when(lifecycle.isAssociatedWithProductive("DEMO-SPENT")).thenAnswer(call -> {
        ranAsSystem.set(thread.isSystemWithAdminMode());
        return true;
      });

      assertTrue(isAssociatedDemo("DEMO-SPENT"));
      assertTrue("the lifecycle read must run as System with admin mode on", ranAsSystem.get());
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());

      assertFalse(isAssociatedDemo("DEMO-FREE"));
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  /**
   * The associated-demo check does not swallow a failure: it propagates, after the caller's
   * context and admin-mode depth have been put back.
   */
  @Test
  public void associatedDemoCheckPropagatesAFailureAfterRestoringTheCaller() throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    IllegalStateException boom = new IllegalStateException("lifecycle unavailable");
    when(lifecycle.isAssociatedWithProductive("DEMO-1")).thenThrow(boom);

    try (ContextThread thread = new ContextThread(caller, 0)) {
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> isAssociatedDemo("DEMO-1"));

      assertSame(boom, thrown);
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  private boolean isProductiveNameTakenByAccount(String clientName, String accountEmail)
      throws Exception {
    return invokePrivateCheck("isProductiveNameTakenByAccount",
        new Class<?>[] { String.class, String.class }, clientName, accountEmail);
  }

  private boolean isAssociatedDemo(String clientId) throws Exception {
    return invokePrivateCheck("isAssociatedDemo", new Class<?>[] { String.class }, clientId);
  }

  /** Invokes a private boolean check, rethrowing its own runtime failure unwrapped. */
  private boolean invokePrivateCheck(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method check = EtendoGoJwtServlet.class.getDeclaredMethod(name, types);
    check.setAccessible(true);
    try {
      return (Boolean) check.invoke(servlet, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * A stand-in for OBContext's per-thread state — the current context and the admin-mode depth —
   * so a spec can see what the code under test leaves behind for its caller.
   */
  private static final class ContextThread implements AutoCloseable {
    final AtomicReference<OBContext> current = new AtomicReference<>();
    final AtomicInteger adminDepth = new AtomicInteger();
    private final AtomicReference<List<String>> systemIds = new AtomicReference<>();
    private final OBContext system = mock(OBContext.class);
    private final MockedStatic<OBContext> statics = mockStatic(OBContext.class);
    private final int callerAdminDepth;

    ContextThread(OBContext caller, int callerAdminDepth) {
      this.callerAdminDepth = callerAdminDepth;
      current.set(caller);
      adminDepth.set(callerAdminDepth);
      statics.when(OBContext::getOBContext).thenAnswer(call -> current.get());
      statics.when(() -> OBContext.setOBContext(anyString(), anyString(), anyString(),
          anyString())).thenAnswer(call -> {
            systemIds.set(List.of(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3)));
            current.set(system);
            return null;
          });
      statics.when(() -> OBContext.setOBContext(ArgumentMatchers.<OBContext>any()))
          .thenAnswer(call -> {
            current.set(call.getArgument(0));
            return null;
          });
      statics.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(call -> {
        adminDepth.incrementAndGet();
        return null;
      });
      statics.when(OBContext::restorePreviousMode).thenAnswer(call -> {
        adminDepth.decrementAndGet();
        return null;
      });
    }

    /**
     * Whether the current context is the one installed for user/role/client/org "0" and admin
     * mode was entered on top of the caller's depth.
     */
    boolean isSystemWithAdminMode() {
      return current.get() == system && List.of("0", "0", "0", "0").equals(systemIds.get())
          && adminDepth.get() > callerAdminDepth;
    }

    @Override
    public void close() {
      statics.close();
    }
  }

  private static final class PurchaseAttempt {
    final ResponseCapture response;
    final HostedCheckoutService checkout;

    PurchaseAttempt(ResponseCapture response, HostedCheckoutService checkout) {
      this.response = response;
      this.checkout = checkout;
    }
  }

  /**
   * Posts a purchase named {@code clientName} from a productive session, with two same-named
   * clients in the instance: one of another account, and one of this account on {@code ownPlan}.
   */
  private PurchaseAttempt postPurchaseNamed(String clientName, String ownPlan) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("PROD-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = mock(DemoDataTransferService.class);
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("PROD-1")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OTHER-ACCOUNT")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OWN-SAME-NAME")).thenReturn(ownPlan);
    when(checkout.createSession(anyString(), anyString(), anyString(), anyString(), any(),
        any(HostedCheckoutService.SessionOptions.class)))
        .thenReturn(new JSONObject().put("requestId", "purchase-1"));
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"" + clientName + "\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("PROD-1", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-SAME-NAME",
          "owner@example.test")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName(clientName))
          .thenReturn(List.of("OTHER-ACCOUNT", "OWN-SAME-NAME"));
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);
    }
    return new PurchaseAttempt(response, checkout);
  }

  private static int countResultLines(String body) {
    int count = 0;
    for (String line : body.split("\n")) {
      if (line.contains("\"type\":\"result\"")) count++;
    }
    return count;
  }

  private static final class PaidOnboardingRun {
    final ResponseCapture response;
    final CheckoutRequestStore store;

    PaidOnboardingRun(ResponseCapture response, CheckoutRequestStore store) {
      this.response = response;
      this.store = store;
    }
  }

  /**
   * Drives a paid onboarding that fails gracefully: either the pooled org-info step throws after
   * the tenant was claimed, or the account already has a productive environment with the company
   * name (ETP-5548).
   */
  private PaidOnboardingRun runPaidOnboarding(boolean pooled, boolean productiveNameTaken)
      throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(pooled);
    doThrow(new OBException("Org info unavailable")).when(servlet.onboardingOrgInfoService)
        .ensureOrgInfo(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    when(servlet.tenantPlanService.resolvePlan("own-productive"))
        .thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    runStagedPaidOnboarding(stage, (support, dal, transactions) -> {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
          .thenReturn(productiveNameTaken ? List.of("other-account-acme", "own-productive")
              : List.of());
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(
          "own-productive", "user@test.com")).thenReturn(true);
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()))
          .thenReturn(true);
    }, (support, dal, transactions) ->
        transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()), never()));
    return new PaidOnboardingRun(stage.response, stage.store);
  }

  /** What one staged paid onboarding sends and the checkout store it closes. */
  private static final class PaidOnboardingStage {
    final ResponseCapture response;
    final HttpServletRequest request;
    final CheckoutRequestStore store;

    PaidOnboardingStage(ResponseCapture response, HttpServletRequest request,
        CheckoutRequestStore store) {
      this.response = response;
      this.request = request;
      this.store = store;
    }
  }

  /** What a staged run executes instead of {@code doPost}, when set (ETP-5048). */
  private StagedAction stagedAction;

  /** A staged run's body other than the HTTP POST. */
  @FunctionalInterface
  private interface StagedAction {
    void run() throws Exception;
  }

  /** Records what a browser-less paid onboarding reports to its sink. */
  private static final class RecordingSink implements com.etendoerp.go.onboarding.OnboardingProgressSink {
    final List<String> lines = new ArrayList<>();

    @Override
    public void progress(String step, String status, String message) {
      lines.add("progress:" + step + ":" + status);
    }

    @Override
    public void result(boolean success, String message, String code) {
      lines.add("result:" + success + ":" + code);
    }
  }

  private static PaidProvisioningService.PaidInput paidInput(String currencyIso) {
    return new PaidProvisioningService.PaidInput("purchase-1", "account-1", "user@test.com",
        "User", "Acme", null, "en_US", currencyIso, "ES", null, null);
  }

  private static PaidProvisioningService.PaidInput paidInput(String countryCode, String address,
      String taxId) {
    return new PaidProvisioningService.PaidInput("purchase-1", "account-1", "user@test.com",
        "User", "Acme", null, "en_US", "EUR", countryCode, address, taxId);
  }

  /**
   * Runs a committed browser-less paid onboarding with these inputs and returns the address and
   * the fiscal identifier the organization step received, plus the outcome and the servlet WARNs.
   */
  private String[] provisionWithCompanyData(PaidProvisioningService.PaidInput input,
      List<String> warnings) throws Exception {
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(input, new RecordingSink());
    com.etendoerp.go.payment.TestLogCapture log = com.etendoerp.go.payment.TestLogCapture
        .attachTo(EtendoGoJwtServlet.class, org.apache.logging.log4j.Level.WARN);
    try {
      runCommittedPaidOnboarding(true, mock(SubscriptionService.class), new ArrayList<>());
      warnings.addAll(log.messagesAt(org.apache.logging.log4j.Level.WARN));
    } finally {
      log.detach();
    }
    assertEquals("Company data never fails the provisioning",
        PaidProvisioningService.Status.PROVISIONED, outcome[0].status());
    ArgumentCaptor<String> address = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> taxId = ArgumentCaptor.forClass(String.class);
    verify(servlet.onboardingOrgInfoService).ensureOrgInfo(anyString(), anyString(), anyString(),
        anyString(), eq(input.countryCode()), address.capture(), taxId.capture());
    return new String[] { address.getValue(), taxId.getValue() };
  }

  /** ETP-5048 S4 — the draft's address and fiscal identifier reach the organization. */
  @Test
  public void aPaidPurchaseCarriesTheDraftAddressAndTaxIdToTheOrganization() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", " Calle Mayor 1 ", "12345678Z"),
        warnings);

    assertEquals("Calle Mayor 1", orgInfo[0]);
    assertEquals("12345678Z", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.stream().noneMatch(w -> w.contains("left out")));
  }

  /** ETP-5048 S4 — an address over the HTTP limit is dropped with a WARN, never truncated. */
  @Test
  public void anOverLongDraftAddressIsDroppedWithAWarning() throws Exception {
    List<String> warnings = new ArrayList<>();
    String tooLong = org.apache.commons.lang3.StringUtils.repeat('a',
        OnboardingFieldLimits.ADDRESS + 1);

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", tooLong, "12345678Z"), warnings);

    assertEquals("", orgInfo[0]);
    assertEquals("The tax id is unaffected", "12345678Z", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.contains("Server-side provisioning of purchase-1: "
        + "the draft address exceeds " + OnboardingFieldLimits.ADDRESS + " characters and is "
        + "left out"));
  }

  /** ETP-5048 S4 — an invalid Spanish tax id is dropped with a WARN, never failing the run. */
  @Test
  public void anInvalidSpanishDraftTaxIdIsDroppedWithAWarning() throws Exception {
    List<String> badCheckDigit = new ArrayList<>();
    String[] orgInfo = provisionWithCompanyData(paidInput("ES", "Calle Mayor 1", "12345678A"),
        badCheckDigit);

    assertEquals("Calle Mayor 1", orgInfo[0]);
    assertEquals("", orgInfo[1]);
    assertTrue(badCheckDigit.toString(), badCheckDigit.contains("Server-side provisioning of "
        + "purchase-1: the draft fiscal identifier is not valid (BAD_CHECK_DIGIT) and is left out"));
  }

  @Test
  public void aMalformedSpanishDraftTaxIdIsDroppedToo() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", "", "not-a-nif"), warnings);

    assertEquals("", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.stream()
        .anyMatch(w -> w.contains("is not valid (BAD_FORMAT) and is left out")));
  }

  /** Only a Spanish identifier is checked: another country's is passed through as given. */
  @Test
  public void aForeignDraftTaxIdIsPassedThroughUnchecked() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("FR", null, "FR12345678901"), warnings);

    assertEquals("A missing address is blank, not null", "", orgInfo[0]);
    assertEquals("FR12345678901", orgInfo[1]);
  }

  /** ETP-5048 — a currency the instance does not know needs review; nothing is claimed. */
  @Test
  public void aPaidPurchaseInAnUnknownCurrencyNeedsReviewAndIsNeverClaimed() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("XXX"),
        new RecordingSink());

    runStagedPaidOnboarding(stage, (support, dal, transactions) -> { },
        (support, dal, transactions) -> { });

    assertEquals(PaidProvisioningService.Status.NEEDS_REVIEW, outcome[0].status());
    assertEquals("UNKNOWN_CURRENCY", outcome[0].code());
    verify(stage.store, never()).claimForProvisioning(anyString(), anyString(), anyString());
  }

  /**
   * ETP-5048 — the reconciliation provisions under the same fence as the browser: when another
   * attempt holds the purchase the claim is refused and no tenant is touched.
   */
  @Test
  public void aPaidPurchaseWhoseClaimIsRefusedRunsNothing() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    when(stage.store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(false);
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);

    runStagedPaidOnboarding(stage, (support, dal, transactions) -> { },
        (support, dal, transactions) -> { });

    assertEquals(PaidProvisioningService.Status.NOT_CLAIMED, outcome[0].status());
    assertNull(outcome[0].attempt());
    verify(stage.store).claimForProvisioning("purchase-1", "account-1", "user@test.com");
    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());
    assertTrue(sink.lines.toString(), sink.lines.isEmpty());
  }

  /**
   * ETP-5048 — a claimed purchase runs the shared onboarding to its commit; the outcome carries
   * the claimed attempt and the sink receives the run's progress and its successful result.
   */
  @Test
  public void aClaimedPaidPurchaseIsProvisionedAndReportsToTheSink() throws Exception {
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);
    List<String> steps = new ArrayList<>();

    runCommittedPaidOnboarding(true, mock(SubscriptionService.class), steps);

    assertEquals(PaidProvisioningService.Status.PROVISIONED, outcome[0].status());
    assertEquals(Long.valueOf(1L), outcome[0].attempt());
    assertTrue(steps.toString(), steps.contains("recordProvisioned"));
    assertTrue(sink.lines.toString(), sink.lines.stream().anyMatch(l -> l.startsWith("progress:")));
    assertEquals("result:true:null", sink.lines.get(sink.lines.size() - 1));
  }

  /** ETP-5048 — a failed run is classified FAILED with the step's own code and the attempt. */
  @Test
  public void aFailedPaidPurchaseRunIsReportedAsFailedWithItsAttempt() throws Exception {
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);

    runPaidOnboarding(true, false);

    assertEquals(PaidProvisioningService.Status.FAILED, outcome[0].status());
    assertEquals(Long.valueOf(1L), outcome[0].attempt());
    assertNotNull(outcome[0].code());
    assertTrue(sink.lines.toString(), sink.lines.get(sink.lines.size() - 1)
        .startsWith("result:false:"));
  }

  /** Per-spec stubs or verifications on the static mocks of a staged paid onboarding. */
  @FunctionalInterface
  private interface StagedStatics {
    void apply(MockedStatic<EtendoGoJwtSupport> support, MockedStatic<EtendoGoJwtDalHelper> dal,
        MockedStatic<EtendoGoDalHelper> transactions);
  }

  /**
   * The setup every paid onboarding spec shares: a confirmed purchase {@code purchase-1} the
   * account {@code account-1} has claimed, the tenant {@code client-1} (pooled or classic), and
   * mocked plan, lifecycle, force-test-mode and org-info services. Spec-specific stubs go on the
   * returned stage or on the servlet's mocks before {@link #runStagedPaidOnboarding}.
   *
   * @param pooled whether the pool hands out {@code client-1}
   */
  private PaidOnboardingStage stagePaidOnboarding(boolean pooled) throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\","
            + "\"paymentToken\":\"purchase-1\"}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com")).thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com")).thenReturn(1L);
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);
    when(servlet.pooledTenantClaimService.claim(any(), any(), anyString()))
        .thenReturn(pooled ? "client-1" : null);
    servlet.devProvisioningFailureFixtureService = mock(DevProvisioningFailureFixtureService.class);
    servlet.tenantPlanService = mock(TenantPlanService.class);
    servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    servlet.onboardingForceTestModeService = mock(OnboardingForceTestModeService.class);
    servlet.onboardingOrgInfoService = mock(com.etendoerp.go.onboarding.OnboardingOrgInfoService.class);
    // ETP-5577 (develop) — finishTenantMetadata marks the initial period state pending, which
    // writes an AD_Preference; a mock keeps the staged run DB-free.
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    return new PaidOnboardingStage(response, request, store);
  }

  /**
   * Posts a staged paid onboarding with the authenticated account, its currency and the
   * {@code client-1} admin role and organizations resolved, inside the static mocks.
   *
   * @param before spec stubs applied before the request
   * @param after spec verifications run before the static mocks close
   */
  private void runStagedPaidOnboarding(PaidOnboardingStage stage, StagedStatics before,
      StagedStatics after) throws Exception {
    Currency currency = mock(Currency.class); when(currency.getId()).thenReturn("currency-1");
    UserRoles admin = mock(UserRoles.class); Role role = mock(Role.class); User user = mock(User.class);
    when(role.getId()).thenReturn("role-1"); when(user.getId()).thenReturn("user-1");
    when(admin.getRole()).thenReturn(role); when(admin.getUserContact()).thenReturn(user);
    Organization org = mock(Organization.class); when(org.getId()).thenReturn("org-1");
    try (var context = mockStatic(OBContext.class);
         var support = mockStatic(EtendoGoJwtSupport.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dal);
      when(authenticated.getId()).thenReturn("account-1");
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com")).thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1")).thenReturn(admin);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1")).thenReturn("star-org");
      dal.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1")).thenReturn(org);
      before.apply(support, dal, transactions);
      if (stagedAction == null) {
        servlet.doPost(stage.request, stage.response.response);
      } else {
        stagedAction.run();
      }
      after.apply(support, dal, transactions);
    }
  }

  private void assertProvisioningAlertOutcome(boolean pooled, boolean complete,
      boolean rollbackConfirmed, boolean deliveryThrows, boolean failAfterCommit) throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\","
            + "\"paymentToken\":\"purchase-1\"}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com")).thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com")).thenReturn(1L);
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);
    when(servlet.pooledTenantClaimService.claim(any(), any(), anyString()))
        .thenReturn(pooled ? "client-1" : null);
    servlet.devProvisioningFailureFixtureService = mock(
        DevProvisioningFailureFixtureService.class);
    servlet.tenantPlanService = mock(TenantPlanService.class);
    when(servlet.tenantPlanService.markProductive("client-1", "star-org")).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(servlet.tenantEnvironmentLifecycleService.markProductive("client-1")).thenReturn(true);
    servlet.onboardingForceTestModeService = mock(OnboardingForceTestModeService.class);
    servlet.onboardingOrgInfoService = mock(com.etendoerp.go.onboarding.OnboardingOrgInfoService.class);
    servlet.onboardingWarehouseAddressService = mock(com.etendoerp.go.onboarding.OnboardingWarehouseAddressService.class);
    servlet.onboardingCostingScheduleService = mock(com.etendoerp.go.onboarding.OnboardingCostingScheduleService.class);
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    if (failAfterCommit) doThrow(new RuntimeException("post-commit scheduler failure"))
        .when(servlet.onboardingCostingScheduleService).activateSchedule("client-1");
    Currency currency = mock(Currency.class); when(currency.getId()).thenReturn("currency-1");
    UserRoles admin = mock(UserRoles.class); Role role = mock(Role.class); User user = mock(User.class);
    when(role.getId()).thenReturn("role-1"); when(user.getId()).thenReturn("user-1");
    when(admin.getRole()).thenReturn(role); when(admin.getUserContact()).thenReturn(user);
    Organization org = mock(Organization.class); when(org.getId()).thenReturn("org-1");
    java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.List<com.etendoerp.go.schemaforge.email.InternalAlertEvent> events = new java.util.ArrayList<>();
    doAnswer(invocation -> {
      assertTrue("Alert must follow the business transaction boundary", settled.get());
      events.add(invocation.getArgument(0));
      if (deliveryThrows) throw new RuntimeException("fake provider unavailable");
      return null;
    }).when(servlet.internalAlertService).sendAfterTransaction(any());
    try (var context = mockStatic(OBContext.class);
         var support = mockStatic(EtendoGoJwtSupport.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dal);
      when(authenticated.getId()).thenReturn("account-1");
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com")).thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
          .thenReturn(pooled ? null : "client-1");
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1")).thenReturn(complete ? admin : null);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1")).thenReturn("star-org");
      dal.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1")).thenReturn(org);
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()))
          .thenAnswer(i -> { settled.set(true); return null; });
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()))
          .thenAnswer(i -> { settled.set(rollbackConfirmed); return rollbackConfirmed; });
      servlet.doPost(request, response.response);
      if (complete) {
        transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()));
        verify(store).recordProvisioned("purchase-1", "client-1", 1L);
        if (!rollbackConfirmed && failAfterCommit)
          verify(store, never()).recordFailureReason(anyString(), anyString());
      } else {
        transactions.verify(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()));
        verify(store, never()).recordProvisioned(anyString(), anyString(), any());
        if (rollbackConfirmed) verify(store).recordFailureReason(eq("purchase-1"), anyString());
        else verify(store, never()).recordFailureReason(anyString(), anyString());
      }
    }
    if (!rollbackConfirmed) verify(unitSession).setDoRollback(true);
    if (!complete && !rollbackConfirmed) {
      assertTrue(events.isEmpty()); verifyNoMoreInteractions(servlet.internalAlertService);
    } else {
      assertEquals(1, events.size());
      com.etendoerp.go.schemaforge.email.InternalAlertEvent event = events.get(0);
      assertEquals(complete ? com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.OK
          : com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.ERROR, event.getStatus());
      assertEquals("PRODUCTIVE", event.getEnvironmentType());
      assertEquals(pooled ? "POOL" : "CLASSIC", event.getPath());
      assertEquals("client-1", event.getClientId());
      assertFalse(event.getAttemptId().equals("purchase-1"));
    }
    assertTrue(response.body().contains(complete && !failAfterCommit ? "\"success\":true" : "\"success\":false"));
  }

  private static HttpServletRequest mockRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  private static HttpServletRequest jsonRequest(String pathInfo, String json) throws Exception {
    HttpServletRequest request = mockRequest(pathInfo);
    when(request.getContentType()).thenReturn("application/json");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    return request;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> {
      capture.contentType = inv.getArgument(0);
      return null;
    }).when(response).setContentType(anyString());
    doAnswer(inv -> {
      capture.encoding = inv.getArgument(0);
      return null;
    }).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;
    String contentType;
    String encoding;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
