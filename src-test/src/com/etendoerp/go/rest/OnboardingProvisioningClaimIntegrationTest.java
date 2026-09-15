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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-5045 — the provisioning claim inside {@code POST /sws/go/onboarding}.
 *
 * <p><b>Why this class exists.</b> {@code handleOnboarding} decides, before it opens the NDJSON
 * stream, whether this request may consume a payment: on a paid upgrade it must claim the checkout
 * request, and on a free one it must not touch the claim at all. That decision had no test of any
 * kind — {@code grep -rln "handleOnboarding" src-test/} returned nothing — while
 * {@code CheckoutRequestStoreIntegrationTest} covered only the store beneath it. A Sonar
 * refactor (cognitive complexity 23 → 14) then extracted the branch into
 * {@code rejectWhenProvisioningAlreadyClaimed}, and its behaviour-preservation rested entirely on
 * reading the boolean. This class is what makes the next such refactor safe.
 *
 * <p><b>The rule that needs the most care.</b> {@code claimForProvisioning} is <em>not</em> a
 * predicate: it is the conditional bulk update that performs the claim, incrementing
 * {@code PROVISIONING_ATTEMPTS} and moving {@code CHECKOUT_STATUS} to {@code PROVISIONING}.
 * Calling it one extra time <em>is</em> the bug. The guard therefore short-circuits on
 * {@code !paidUpgrade}, and a spec that only asserted "the free request succeeded" would pass even
 * if that short-circuit were lost — so the free spec counts the calls instead.
 *
 * <p><b>Why the free fixture carries a non-blank token.</b> A free request with a <em>null</em>
 * token is absorbed by {@code CheckoutRequestStore.claimForProvisioning}'s own
 * {@code isBlank(requestId)} guard, which returns false before touching the session. A spec built
 * on that cell would be green for a reason unrelated to what it claims to test, and would stay
 * green if the short-circuit were replaced by a non-short-circuiting {@code |}. The fixture here
 * therefore carries a token that names a <em>real, PAID</em> checkout request, because
 * {@code isPaidFor} matches on the environment name while {@code claimForProvisioning} does not —
 * so the paywall answers FREE while the claim, if it ever ran, would succeed against another
 * request's row. That asymmetry is the whole hazard, and it is silent: the user onboarding sees
 * nothing, and the damage lands on a different request that is left stuck in
 * {@code PROVISIONING}.
 *
 * <p><b>What is real.</b> Everything except the HTTP envelope: a real account resolved by its
 * session token, a real paywall reading real {@code ETGO_CHECKOUT_REQUEST} rows, and the servlet's
 * own routing through {@code doPost}. Nothing is stubbed to produce an outcome — the fixtures are
 * arranged so the production paywall reaches FREE or PAID on its own.
 *
 * <p><b>How provisioning is stopped.</b> Past the claim the servlet opens an NDJSON stream and
 * provisions an entire tenant, which no test may do. The specs that get past the claim therefore
 * make {@code response.getWriter()} throw {@link IOException} — a real failure mode, the client
 * disconnecting before the stream opens — which happens strictly <em>after</em> the decision under
 * test and so cannot influence it. {@link #assertNothingWasProvisioned} verifies no client was
 * created regardless.
 */
public class OnboardingProvisioningClaimIntegrationTest extends OBBaseTest {

  private static final String MARKER = "etp5045-onb-";
  private static final String ZERO = "0";
  private static final String ONBOARDING_PATH = "/onboarding";

  private static final String PAID_ENVIRONMENT = "ETP-5045 Onboarding Paid Environment";
  private static final String OTHER_ENVIRONMENT = "ETP-5045 Onboarding Other Environment";

  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_PROVISIONING = "PROVISIONING";

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
  private final CheckoutRequestStore fixtureStore = new CheckoutRequestStore();
  private long clientsBefore;

  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The free path must not touch the claim at all
  // ---------------------------------------------------------------------------------------------

  /**
   * The cell the whole class exists for: a free onboarding that nonetheless carries a token naming
   * a real paid request must never call {@code claimForProvisioning}.
   *
   * <p>If the guard's short-circuit were lost — a {@code |} instead of {@code ||}, or the operands
   * reordered — the claim would run against a request this onboarding has no relationship to. It
   * would succeed, because {@code claimForProvisioning} matches on correlation id and account
   * e-mail but not on environment name, and the victim request would be left in
   * {@code PROVISIONING} with an incremented attempt counter, waiting for a run that will never
   * come. Nothing surfaces at the time: this request proceeds exactly as it should.
   */
  @Test
  public void testAFreeOnboardingNeverClaimsEvenWhenItCarriesAPaidToken() throws Exception {
    String email = newEmail("free-with-token");
    String token = createAccount(email);
    // Paid for one environment; this request onboards a different one, so the paywall answers FREE
    // while the claim — which ignores the environment name — would still match this row.
    String requestId = createPaidRequest(email, PAID_ENVIRONMENT);
    CountingCheckoutRequestStore counting = installCountingStore();

    try {
      servlet.doPost(onboardingRequest(token, OTHER_ENVIRONMENT, requestId),
          responseWhoseStreamIsAlreadyGone());
      fail("the stream was closed, so the request cannot have completed");
    } catch (IOException expected) {
      // Thrown by getWriter(), strictly after the decision under test.
    }

    // The consequence is asserted before the mechanism, so a regression reports the corruption
    // it caused rather than a bare call count.
    assertEquals("A free onboarding must leave an unrelated paid request untouched — claiming it "
        + "strands that request in PROVISIONING waiting for a run that will never come",
        STATUS_PAID, rawRequest(requestId, "CHECKOUT_STATUS"));
    assertEquals(0L, rawAttempts(requestId));
    assertNull(rawRequest(requestId, "PROVISIONING_AT"));
    assertEquals("and the claim must not have been called at all — it is a bulk update, not a "
        + "predicate, so calling it is itself the damage", 0, counting.claimCalls);
    assertNothingWasProvisioned();
  }

  /**
   * The same rule with no token at all, which is the ordinary free onboarding.
   *
   * <p>Kept alongside the spec above rather than instead of it, but it proves strictly less. A
   * lost short-circuit is still <em>detected</em> here, because the call is counted before the
   * store sees it — but it would do no <em>harm</em>, since
   * {@code claimForProvisioning}'s own {@code isBlank(requestId)} guard returns before touching
   * the session. So this spec pins the ordinary path and would catch the regression, while the
   * spec above is the one that demonstrates what the regression actually costs.
   */
  @Test
  public void testAnOrdinaryFreeOnboardingDoesNotClaim() throws Exception {
    String email = newEmail("free-no-token");
    String token = createAccount(email);
    CountingCheckoutRequestStore counting = installCountingStore();

    try {
      servlet.doPost(onboardingRequest(token, OTHER_ENVIRONMENT, ""),
          responseWhoseStreamIsAlreadyGone());
      fail("the stream was closed, so the request cannot have completed");
    } catch (IOException expected) {
      // as above
    }

    assertEquals(0, counting.claimCalls);
    assertNothingWasProvisioned();
  }

  // ---------------------------------------------------------------------------------------------
  // The paid path must claim exactly once, and a second attempt must be refused
  // ---------------------------------------------------------------------------------------------

  /**
   * The paid branch: a confirmed payment for this account and this environment name claims the
   * request before the stream opens, which is what moves it to {@code PROVISIONING} and opens the
   * attempt counter.
   *
   * <p>Claiming <em>before</em> the stream is deliberate and worth pinning at this layer: a
   * refusal has to answer with plain JSON, and it cannot once NDJSON is flowing.
   */
  @Test
  public void testAPaidOnboardingClaimsTheRequestOnceBeforeTheStreamOpens() throws Exception {
    String email = newEmail("paid-first");
    String token = createAccount(email);
    String requestId = createPaidRequest(email, PAID_ENVIRONMENT);
    CountingCheckoutRequestStore counting = installCountingStore();

    try {
      servlet.doPost(onboardingRequest(token, PAID_ENVIRONMENT, requestId),
          responseWhoseStreamIsAlreadyGone());
      fail("the stream was closed, so the request cannot have completed");
    } catch (IOException expected) {
      // Thrown by getWriter(), i.e. after the claim has already been decided and committed.
    }

    assertEquals("A paid upgrade must claim exactly once", 1, counting.claimCalls);
    assertEquals(STATUS_PROVISIONING, rawRequest(requestId, "CHECKOUT_STATUS"));
    assertEquals(1L, rawAttempts(requestId));
    assertNotNull("PROVISIONING_AT is stamped by the claim itself",
        rawRequest(requestId, "PROVISIONING_AT"));
    assertNothingWasProvisioned();
  }

  /**
   * The reload-during-provisioning guard, at the layer that answers the browser. The
   * {@code ?checkout=success} URL stays live for the whole slow provisioning run, so a refresh
   * fires a second onboarding for the same payment. Exactly one may proceed.
   *
   * <p>This spec needs no artificial stream failure: the refusal happens before the stream opens,
   * which is precisely the property being pinned. A 409 with a plain JSON body — rather than a
   * half-built NDJSON stream — is only possible because the claim runs first.
   */
  @Test
  public void testASecondOnboardingForTheSamePaymentIsRefusedWithConflict() throws Exception {
    String email = newEmail("paid-second");
    String token = createAccount(email);
    String requestId = createPaidRequest(email, PAID_ENVIRONMENT);
    // The state a provisioning run in flight leaves behind.
    assertTrue("Sanity: the first caller must win the claim",
        fixtureStore.claimForProvisioning(requestId, email));
    CountingCheckoutRequestStore counting = installCountingStore();

    ResponseCapture response = mockResponse();
    servlet.doPost(onboardingRequest(token, PAID_ENVIRONMENT, requestId), response.response);

    assertEquals(409, response.status);
    JSONObject error = new JSONObject(response.body()).getJSONObject("error");
    assertEquals("PROVISIONING_ALREADY_IN_PROGRESS", error.getString("code"));
    assertEquals("The refusal must be plain JSON, which is only possible because the claim runs "
        + "before the NDJSON stream opens", "application/json", response.contentType);
    assertEquals("The paid path consulted the claim", 1, counting.claimCalls);
    assertEquals("A refused claim matches no row, so it cannot advance the counter", 1L,
        rawAttempts(requestId));
    assertEquals(STATUS_PROVISIONING, rawRequest(requestId, "CHECKOUT_STATUS"));
    assertNothingWasProvisioned();
  }

  // ---------------------------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------------------------

  /** Delegates to the real store while counting the calls that matter. */
  private static final class CountingCheckoutRequestStore extends CheckoutRequestStore {
    private final CheckoutRequestStore delegate;
    private int claimCalls;

    CountingCheckoutRequestStore(CheckoutRequestStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean claimForProvisioning(String requestId, String accountEmail) {
      claimCalls++;
      return delegate.claimForProvisioning(requestId, accountEmail);
    }
  }

  private CountingCheckoutRequestStore installCountingStore() {
    CountingCheckoutRequestStore counting =
        new CountingCheckoutRequestStore(servlet.checkoutRequestStore);
    servlet.checkoutRequestStore = counting;
    return counting;
  }

  /**
   * Builds one onboarding POST. Only {@code clientName} is mandatory in the body; currency,
   * language and country all default, so the fixture states exactly what the spec depends on.
   *
   * @param sessionToken the account's bearer token
   * @param clientName requested environment name
   * @param paymentToken the checkout correlation id, or empty for an ordinary free onboarding
   * @return the mocked request
   */
  private HttpServletRequest onboardingRequest(String sessionToken, String clientName,
      String paymentToken) throws Exception {
    JSONObject body = new JSONObject();
    body.put("clientName", clientName);
    body.put("paymentToken", paymentToken);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(ONBOARDING_PATH);
    when(request.getMethod()).thenReturn("POST");
    when(request.getContentType()).thenReturn("application/json");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + sessionToken);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));
    return request;
  }

  /**
   * A response whose stream is already gone, so provisioning cannot start.
   *
   * <p>{@code getWriter()} throwing {@link IOException} is what a disconnected client produces,
   * and {@code handleOnboarding} declares {@code throws IOException} with no handler around the
   * stream setup — so it propagates and nothing is provisioned. It happens after the claim
   * decision has been made and committed, which is why it cannot affect what these specs assert.
   *
   * @return a response mock that refuses to hand out a writer
   */
  private HttpServletResponse responseWhoseStreamIsAlreadyGone() throws IOException {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenThrow(new IOException("client disconnected"));
    return response;
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  private String newEmail(String label) {
    return MARKER + label + "-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
  }

  /**
   * Creates an account the servlet can resolve from a bearer token, and records how many clients
   * exist so {@link #assertNothingWasProvisioned} can prove none were added.
   *
   * <p>The account is deliberately left with no verification columns: {@code
   * isEmailVerificationPending} is true only when a verify-token hash is present without a
   * verified date, so a fresh account passes the e-mail gate without any extra fixture. It owns no
   * environment either, which is what makes the paywall's decision "allowed" and leaves the
   * productive flag to be decided by the payment alone.
   *
   * @param email the account e-mail
   * @return the session token to authenticate with
   */
  private String createAccount(String email) {
    clientsBefore = rawClientCount();
    String sessionToken = MARKER + UUID.randomUUID().toString().replace("-", "");
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Account account = OBProvider.getInstance().get(Account.class);
      account.setNewOBObject(true);
      account.setClient(OBDal.getInstance().get(Client.class, ZERO));
      account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      account.setActive(true);
      account.setEmail(email);
      account.setName("ETP-5045 onboarding claim fixture");
      account.setStatus("active");
      account.setSessionToken(sessionToken);
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      return sessionToken;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Drives a checkout request through the real store to a confirmed payment, which is what makes
   * the production paywall answer PAID for the matching environment name.
   *
   * @param email owning account e-mail
   * @param clientName the environment the payment is for
   * @return the correlation id
   */
  private String createPaidRequest(String email, String clientName) {
    String accountId = accountIdFor(email);
    String requestId = MARKER + UUID.randomUUID().toString().replace("-", "");
    fixtureStore.recordRequested(requestId, accountId, email, clientName);
    fixtureStore.recordSessionCreated(requestId, "cs_" + requestId);
    fixtureStore.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);
    return requestId;
  }

  @SuppressWarnings("rawtypes")
  private String accountIdFor(String email) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT ETGO_ACCOUNT_ID FROM ETGO_ACCOUNT WHERE EMAIL = :email");
      query.setParameter("email", email);
      return (String) query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers and cleanup
  // ---------------------------------------------------------------------------------------------

  @SuppressWarnings("rawtypes")
  private Object rawRequest(String requestId, String column) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT " + column + " FROM ETGO_CHECKOUT_REQUEST "
              + "WHERE REQUEST_ID = :requestId");
      query.setParameter("requestId", requestId);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private long rawAttempts(String requestId) {
    return ((Number) rawRequest(requestId, "PROVISIONING_ATTEMPTS")).longValue();
  }

  @SuppressWarnings("rawtypes")
  private long rawClientCount() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT COUNT(*) FROM AD_CLIENT");
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Proves the specs stopped where they claim to. A tenant created by a test would be slow,
   * unreviewable and effectively impossible to clean up, so "no client was added" is asserted
   * rather than assumed.
   */
  private void assertNothingWasProvisioned() {
    assertEquals("No tenant may be created by these specs", clientsBefore, rawClientCount());
  }

  /**
   * Removes everything this class committed, by marker, requests before accounts so the FK holds.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery deleteRequests = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker");
      deleteRequests.setParameter("marker", MARKER + "%");
      deleteRequests.executeUpdate();

      NativeQuery deleteAccounts = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker");
      deleteAccounts.setParameter("marker", MARKER + "%");
      deleteAccounts.executeUpdate();

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static ResponseCapture mockResponse() {
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
    doAnswer(inv -> null).when(response).setCharacterEncoding(anyString());
    try {
      when(response.getWriter()).thenReturn(writer);
    } catch (IOException e) {
      throw new IllegalStateException("mocking getWriter cannot fail", e);
    }
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;
    String contentType;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
