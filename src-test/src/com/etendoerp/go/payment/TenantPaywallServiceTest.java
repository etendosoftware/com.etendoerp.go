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

package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.etendoerp.go.payment.TenantPaywallService.Decision;
import com.etendoerp.go.payment.TenantPaywallService.Outcome;

/**
 * Specs for the paid-environment evaluation (ETP-4966).
 *
 * <p>Two rules are under test, and keeping them apart is the whole point of this class:
 * <ul>
 *   <li><strong>the decision</strong> — may this request provision at all;</li>
 *   <li><strong>the plan</strong> — does the resulting environment become productive.</li>
 * </ul>
 *
 * <p>ETP-4966 was reported as "I paid with Stripe and my environment is still badged Demo". The
 * cause was that the plan was inferred from the decision instead of from the payment: with the
 * {@code tenant-upgrade} flag unset — which was the case in experimental, staging and production —
 * the paywall short-circuited to {@code ALLOWED} without ever reading the payment token, so a
 * charged account got a free environment and nothing anywhere reported it. The plan must therefore
 * be derived from the one fact that means money changed hands: a
 * {@link CheckoutRequestStore}-confirmed payment, correlated to this account and this
 * environment name by the Stripe webhook.
 *
 * <p>There is no flag parameter. The paid-environment capability is permanent and cannot be
 * switched off, which is what removes the class of bug where the two ends of the system disagree
 * about whether the feature is on.
 */
public class TenantPaywallServiceTest {

  /** Distinct per call so tests never collide in the shared CONFIRMED map below. */
  private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

  private static final String BUYER = "buyer@example.test";
  private static final String OTHER_ACCOUNT = "someone.else@example.test";
  private static final String ENVIRONMENT = "Acme Productive";
  private static final String OTHER_ENVIRONMENT = "Acme Something Else";

  // Same shape the retired MockPaymentService used to accept. Kept as a constant specifically to
  // prove it is no longer special-cased: with no matching CheckoutRequestStore row, a
  // mock-shaped token must be declined exactly like any other unverified string.
  private static final String MOCK_SHAPED_TOKEN = "mock-paid-abc123";

  /** Stands in for the durable store: request id -> the account and environment it was paid for. */
  private static final Map<String, String[]> CONFIRMED = new HashMap<>();

  private final TenantPaywallService service = new TenantPaywallService();

  {
    // Mirrors CheckoutRequestStore.isPaidFor, including its blank-clientName rule: a caller that
    // does not know the environment name gets the account check only.
    service.paymentConfirmation = (requestId, accountEmail, clientName) -> {
      String[] recorded = requestId == null ? null : CONFIRMED.get(requestId);
      if (recorded == null || !recorded[0].equalsIgnoreCase(accountEmail)) {
        return false;
      }
      return clientName == null || clientName.trim().isEmpty()
          || recorded[1].equalsIgnoreCase(clientName);
    };
  }

  /** Records a webhook-confirmed payment and returns the token that correlates to it. */
  private static String confirmedPaymentFor(String accountEmail, String clientName) {
    String requestId = "req-" + REQUEST_SEQUENCE.incrementAndGet() + "-"
        + System.identityHashCode(new Object());
    CONFIRMED.put(requestId, new String[] { accountEmail, clientName });
    return requestId;
  }

  /** An additional environment: the account already owns one and is not resuming or converting. */
  private Outcome evaluateAdditionalEnvironment(String paymentToken) {
    return service.evaluate(true, false, false, paymentToken, BUYER, ENVIRONMENT);
  }

  // --- The reported bug: a confirmed payment is what makes an environment productive ---

  @Test
  public void confirmedPaymentForAnAdditionalEnvironmentIsAllowedAndProductive() {
    Outcome outcome = evaluateAdditionalEnvironment(confirmedPaymentFor(BUYER, ENVIRONMENT));

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue("a payment the Stripe webhook confirmed must produce a productive environment",
        outcome.isProductive());
  }

  @Test
  public void confirmedPaymentIsProductiveEvenForAnAccountThatOwnsNoEnvironmentYet() {
    // A first environment is free, so this request was never going to be blocked. It is still a
    // completed purchase: the account paid and must get what it paid for. Deriving the plan from
    // ownership instead of from the payment is exactly what shipped a charged account a demo.
    Outcome outcome = service.evaluate(false, false, false,
        confirmedPaymentFor(BUYER, ENVIRONMENT), BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue("a confirmed payment must be honoured even when the paywall would have allowed "
        + "the request for free", outcome.isProductive());
  }

  // --- Converting the environment the user is currently in ---

  @Test
  public void convertingTheCurrentEnvironmentWithAConfirmedPaymentIsAllowedAndProductive() {
    // The web client preselects this: upgradeAction=convert-demo against the environment the
    // session is already inside, so the requested name resolves to an environment the account
    // owns. That makes it a resume as far as client lookup is concerned, and a paid state
    // transition as far as the plan is concerned.
    Outcome outcome = service.evaluate(true, true, true,
        confirmedPaymentFor(BUYER, ENVIRONMENT), BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue("converting the current environment is the paid transition this feature exists "
        + "for", outcome.isProductive());
  }

  @Test
  public void convertingTheCurrentEnvironmentWithoutAPaymentIsRefused() {
    // Conversion must not be reachable as a free retry of interrupted onboarding: without a
    // payment there is nothing to convert, so the request is refused rather than silently
    // re-provisioning the same environment on the free plan.
    Outcome outcome = service.evaluate(true, true, true, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.PAYMENT_REQUIRED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  public void convertingWithATokenNobodyConfirmedIsDeclined() {
    Outcome outcome = service.evaluate(true, true, true, MOCK_SHAPED_TOKEN, BUYER, ENVIRONMENT);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- Resuming an interrupted provisioning is not a purchase ---

  @Test
  public void resumingAnOwnedEnvironmentIsAllowedFreeOfChargeAndStaysOnItsCurrentPlan() {
    // A partially provisioned environment is re-entered so the idempotent chain can reconcile
    // what is missing. No payment, and no plan change: it must not be charged, and it must not be
    // promoted either.
    Outcome outcome = service.evaluate(true, true, false, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  public void resumingIsNotBlockedByAnUnusableToken() {
    Outcome outcome = service.evaluate(true, true, false, MOCK_SHAPED_TOKEN, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- A first environment is free ---

  @Test
  public void firstEnvironmentNeedsNoPaymentAndIsNotProductive() {
    Outcome outcome = service.evaluate(false, false, false, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- An additional environment is gated on a confirmed payment ---

  @Test
  public void additionalEnvironmentWithoutATokenRequiresPayment() {
    // Absent, empty and whitespace-only must all land on PAYMENT_REQUIRED rather than
    // PAYMENT_DECLINED: none of them is a token someone tried and failed to pay with.
    for (String paymentToken : new String[] { null, "", "   " }) {
      Outcome outcome = evaluateAdditionalEnvironment(paymentToken);

      assertEquals("expected PAYMENT_REQUIRED for " + describe(paymentToken),
          Decision.PAYMENT_REQUIRED, outcome.getDecision());
      assertFalse(outcome.isProductive());
    }
  }

  /** Names a token in an assertion message, so a failure on "" or "   " is still readable. */
  private static String describe(String paymentToken) {
    return paymentToken == null ? "a null token" : "the token '" + paymentToken + "'";
  }

  @Test
  public void additionalEnvironmentWithAnUnconfirmedTokenIsDeclined() {
    Outcome outcome = evaluateAdditionalEnvironment("req-never-paid");

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  public void additionalEnvironmentWithAMockShapedButUnconfirmedTokenIsDeclined() {
    // Regression test: a hand-crafted token that merely LOOKS like the retired mock-payment
    // format must not bypass the paywall, and must never mark an environment productive.
    Outcome outcome = evaluateAdditionalEnvironment(MOCK_SHAPED_TOKEN);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- A payment belongs to one account and one environment name ---

  @Test
  public void aPaymentConfirmedForAnotherAccountIsNeitherAllowedNorProductive() {
    String foreignPayment = confirmedPaymentFor(OTHER_ACCOUNT, ENVIRONMENT);

    Outcome outcome = evaluateAdditionalEnvironment(foreignPayment);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse("one account's payment must never promote another account's environment",
        outcome.isProductive());
  }

  @Test
  public void aPaymentConfirmedForAnotherEnvironmentNameIsNeitherAllowedNorProductive() {
    String otherEnvironmentPayment = confirmedPaymentFor(BUYER, OTHER_ENVIRONMENT);

    Outcome outcome = evaluateAdditionalEnvironment(otherEnvironmentPayment);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse("a payment raised for one environment name must not promote a different one",
        outcome.isProductive());
  }

  // --- Invariants ---

  @Test
  public void aBlockedRequestIsNeverProductive() {
    // Nothing was provisioned, so there is nothing to promote. Stated as its own spec because a
    // productive marker on a refused request would silently grant the paid plan for free.
    for (String token : new String[] { null, "", "req-never-paid", MOCK_SHAPED_TOKEN }) {
      Outcome outcome = evaluateAdditionalEnvironment(token);
      assertTrue("expected a blocked decision for " + token, outcome.getDecision().isBlocked());
      assertFalse("a blocked request must never be productive: " + token,
          outcome.isProductive());
    }
  }

  @Test
  public void onlyAllowedIsUnblocked() {
    assertFalse(Decision.ALLOWED.isBlocked());
    assertTrue(Decision.PAYMENT_REQUIRED.isBlocked());
    assertTrue(Decision.PAYMENT_DECLINED.isBlocked());
  }

  @Test
  public void theSameRequestEvaluatesTheSameWayEveryTime() {
    // The capability has no off switch, so two identical evaluations cannot disagree. This is the
    // unit-level statement of "it can no longer be turned off": there is no ambient configuration
    // left for the outcome to depend on.
    String paymentToken = confirmedPaymentFor(BUYER, ENVIRONMENT);

    Outcome first = evaluateAdditionalEnvironment(paymentToken);
    Outcome second = evaluateAdditionalEnvironment(paymentToken);

    assertEquals(first.getDecision(), second.getDecision());
    assertEquals(first.isProductive(), second.isProductive());
  }
}
