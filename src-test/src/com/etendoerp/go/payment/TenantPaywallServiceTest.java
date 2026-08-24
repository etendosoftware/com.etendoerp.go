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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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
 * {@link CheckoutPaymentRegistry}-confirmed payment, correlated to this account and this
 * environment name by the Stripe webhook.
 *
 * <p>There is no flag parameter. The paid-environment capability is permanent and cannot be
 * switched off, which is what removes the class of bug where the two ends of the system disagree
 * about whether the feature is on.
 */
class TenantPaywallServiceTest {

  /** Distinct per call so tests never collide in the process-wide payment registry. */
  private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

  private static final String BUYER = "buyer@example.test";
  private static final String OTHER_ACCOUNT = "someone.else@example.test";
  private static final String ENVIRONMENT = "Acme Productive";
  private static final String OTHER_ENVIRONMENT = "Acme Something Else";

  // Same shape the retired MockPaymentService used to accept. Kept as a constant specifically to
  // prove it is no longer special-cased: with no matching CheckoutPaymentRegistry entry, a
  // mock-shaped token must be declined exactly like any other unverified string.
  private static final String MOCK_SHAPED_TOKEN = "mock-paid-abc123";

  private final TenantPaywallService service = new TenantPaywallService();

  /** Records a webhook-confirmed payment and returns the token that correlates to it. */
  private static String confirmedPaymentFor(String accountEmail, String clientName) {
    String requestId = "req-" + REQUEST_SEQUENCE.incrementAndGet() + "-"
        + System.identityHashCode(new Object());
    CheckoutPaymentRegistry.recordPaid(requestId, accountEmail, clientName);
    return requestId;
  }

  /** An additional environment: the account already owns one and is not resuming or converting. */
  private Outcome evaluateAdditionalEnvironment(String paymentToken) {
    return service.evaluate(true, false, false, paymentToken, BUYER, ENVIRONMENT);
  }

  // --- The reported bug: a confirmed payment is what makes an environment productive ---

  @Test
  void confirmedPaymentForAnAdditionalEnvironmentIsAllowedAndProductive() {
    Outcome outcome = evaluateAdditionalEnvironment(confirmedPaymentFor(BUYER, ENVIRONMENT));

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue(outcome.isProductive(),
        "a payment the Stripe webhook confirmed must produce a productive environment");
  }

  @Test
  void confirmedPaymentIsProductiveEvenForAnAccountThatOwnsNoEnvironmentYet() {
    // A first environment is free, so this request was never going to be blocked. It is still a
    // completed purchase: the account paid and must get what it paid for. Deriving the plan from
    // ownership instead of from the payment is exactly what shipped a charged account a demo.
    Outcome outcome = service.evaluate(false, false, false,
        confirmedPaymentFor(BUYER, ENVIRONMENT), BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue(outcome.isProductive(),
        "a confirmed payment must be honoured even when the paywall would have allowed the "
            + "request for free");
  }

  // --- Converting the environment the user is currently in ---

  @Test
  void convertingTheCurrentEnvironmentWithAConfirmedPaymentIsAllowedAndProductive() {
    // The web client preselects this: upgradeAction=convert-demo against the environment the
    // session is already inside, so the requested name resolves to an environment the account
    // owns. That makes it a resume as far as client lookup is concerned, and a paid state
    // transition as far as the plan is concerned.
    Outcome outcome = service.evaluate(true, true, true,
        confirmedPaymentFor(BUYER, ENVIRONMENT), BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertTrue(outcome.isProductive(),
        "converting the current environment is the paid transition this feature exists for");
  }

  @Test
  void convertingTheCurrentEnvironmentWithoutAPaymentIsRefused() {
    // Conversion must not be reachable as a free retry of interrupted onboarding: without a
    // payment there is nothing to convert, so the request is refused rather than silently
    // re-provisioning the same environment on the free plan.
    Outcome outcome = service.evaluate(true, true, true, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.PAYMENT_REQUIRED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  void convertingWithATokenNobodyConfirmedIsDeclined() {
    Outcome outcome = service.evaluate(true, true, true, MOCK_SHAPED_TOKEN, BUYER, ENVIRONMENT);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- Resuming an interrupted provisioning is not a purchase ---

  @Test
  void resumingAnOwnedEnvironmentIsAllowedFreeOfChargeAndStaysOnItsCurrentPlan() {
    // A partially provisioned environment is re-entered so the idempotent chain can reconcile
    // what is missing. No payment, and no plan change: it must not be charged, and it must not be
    // promoted either.
    Outcome outcome = service.evaluate(true, true, false, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  void resumingIsNotBlockedByAnUnusableToken() {
    Outcome outcome = service.evaluate(true, true, false, MOCK_SHAPED_TOKEN, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- A first environment is free ---

  @Test
  void firstEnvironmentNeedsNoPaymentAndIsNotProductive() {
    Outcome outcome = service.evaluate(false, false, false, null, BUYER, ENVIRONMENT);

    assertEquals(Decision.ALLOWED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- An additional environment is gated on a confirmed payment ---

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void additionalEnvironmentWithoutATokenRequiresPayment(String paymentToken) {
    Outcome outcome = evaluateAdditionalEnvironment(paymentToken);

    assertEquals(Decision.PAYMENT_REQUIRED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  void additionalEnvironmentWithAnUnconfirmedTokenIsDeclined() {
    Outcome outcome = evaluateAdditionalEnvironment("req-never-paid");

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  @Test
  void additionalEnvironmentWithAMockShapedButUnconfirmedTokenIsDeclined() {
    // Regression test: a hand-crafted token that merely LOOKS like the retired mock-payment
    // format must not bypass the paywall, and must never mark an environment productive.
    Outcome outcome = evaluateAdditionalEnvironment(MOCK_SHAPED_TOKEN);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive());
  }

  // --- A payment belongs to one account and one environment name ---

  @Test
  void aPaymentConfirmedForAnotherAccountIsNeitherAllowedNorProductive() {
    String foreignPayment = confirmedPaymentFor(OTHER_ACCOUNT, ENVIRONMENT);

    Outcome outcome = evaluateAdditionalEnvironment(foreignPayment);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive(),
        "one account's payment must never promote another account's environment");
  }

  @Test
  void aPaymentConfirmedForAnotherEnvironmentNameIsNeitherAllowedNorProductive() {
    String otherEnvironmentPayment = confirmedPaymentFor(BUYER, OTHER_ENVIRONMENT);

    Outcome outcome = evaluateAdditionalEnvironment(otherEnvironmentPayment);

    assertEquals(Decision.PAYMENT_DECLINED, outcome.getDecision());
    assertFalse(outcome.isProductive(),
        "a payment raised for one environment name must not promote a different one");
  }

  // --- Invariants ---

  @Test
  void aBlockedRequestIsNeverProductive() {
    // Nothing was provisioned, so there is nothing to promote. Stated as its own spec because a
    // productive marker on a refused request would silently grant the paid plan for free.
    for (String token : new String[] { null, "", "req-never-paid", MOCK_SHAPED_TOKEN }) {
      Outcome outcome = evaluateAdditionalEnvironment(token);
      assertTrue(outcome.getDecision().isBlocked(), "expected a blocked decision for " + token);
      assertFalse(outcome.isProductive(), "a blocked request must never be productive: " + token);
    }
  }

  @Test
  void onlyAllowedIsUnblocked() {
    assertFalse(Decision.ALLOWED.isBlocked());
    assertTrue(Decision.PAYMENT_REQUIRED.isBlocked());
    assertTrue(Decision.PAYMENT_DECLINED.isBlocked());
  }

  @Test
  void theSameRequestEvaluatesTheSameWayEveryTime() {
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
