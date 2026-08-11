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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.payment.TenantPaywallService.Decision;

class TenantPaywallServiceTest {

  // Same shape the retired MockPaymentService used to accept. Kept as a constant specifically to
  // prove it is no longer special-cased: with no matching CheckoutPaymentRegistry entry, a
  // mock-shaped token must be declined exactly like any other unverified string.
  private static final String MOCK_SHAPED_TOKEN = "mock-paid-abc123";
  private static final String DECLINED_TOKEN = "mock-declined";

  private final TenantPaywallService service = new TenantPaywallService();

  // --- Flag off: the pre-feature behaviour must be preserved exactly ---

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  void flagOffAllowsRegardlessOfOwnership(boolean ownsTenant) {
    assertEquals(Decision.ALLOWED, service.decide(false, ownsTenant, false, null));
  }

  @Test
  void flagOffIgnoresAnUnusableToken() {
    assertEquals(Decision.ALLOWED, service.decide(false, true, false, DECLINED_TOKEN));
  }

  @Test
  void flagOffIgnoresEvenAMockShapedToken() {
    assertEquals(Decision.ALLOWED, service.decide(false, true, false, MOCK_SHAPED_TOKEN));
  }

  // --- First tenant is always free ---

  @Test
  void firstTenantNeedsNoPaymentWhenFlagIsOn() {
    assertEquals(Decision.ALLOWED, service.decide(true, false, false, null));
  }

  // --- Additional tenant is gated on payment ---

  @Test
  void additionalTenantWithoutTokenRequiresPayment() {
    assertEquals(Decision.PAYMENT_REQUIRED, service.decide(true, true, false, null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void additionalTenantWithBlankTokenRequiresPayment(String token) {
    assertEquals(Decision.PAYMENT_REQUIRED, service.decide(true, true, false, token));
  }

  @Test
  void additionalTenantWithDeclinedTokenIsRefused() {
    assertEquals(Decision.PAYMENT_DECLINED, service.decide(true, true, false, DECLINED_TOKEN));
  }

  @Test
  void additionalTenantWithMalformedTokenIsRefused() {
    assertEquals(Decision.PAYMENT_DECLINED, service.decide(true, true, false, "mock-paid-NOTHEX"));
  }

  @Test
  void additionalTenantWithMockShapedButUnconfirmedTokenIsDeclined() {
    // Regression test: a hand-crafted token that merely LOOKS like the retired mock-payment
    // format must not bypass the paywall. Only a CheckoutPaymentRegistry-confirmed Stripe payment
    // (see the overload tests below) may return ALLOWED for an additional tenant.
    assertEquals(Decision.PAYMENT_DECLINED, service.decide(true, true, false, MOCK_SHAPED_TOKEN));
  }

  // --- Resuming a tenant the account already owns is not a new tenant ---

  @Test
  void resumingAnOwnedTenantIsNotCharged() {
    assertEquals(Decision.ALLOWED, service.decide(true, true, true, null));
  }

  @Test
  void resumingTakesPrecedenceOverADeclinedToken() {
    assertEquals(Decision.ALLOWED, service.decide(true, true, true, DECLINED_TOKEN));
  }

  // --- Decision semantics used by the servlet to pick the HTTP status ---

  @Test
  void onlyAllowedIsUnblocked() {
    assertFalse(Decision.ALLOWED.isBlocked());
    assertTrue(Decision.PAYMENT_REQUIRED.isBlocked());
    assertTrue(Decision.PAYMENT_DECLINED.isBlocked());
  }

  // --- Only a webhook-confirmed Stripe payment may pass the paywall for an additional tenant ---

  @Test
  void additionalTenantIsAllowedWhenCheckoutPaymentRegistryConfirmsIt() {
    String requestId = "req-" + System.identityHashCode(new Object());
    String accountEmail = "buyer@example.test";
    String clientName = "Acme Additional Tenant";
    CheckoutPaymentRegistry.recordPaid(requestId, accountEmail, clientName);

    assertEquals(Decision.ALLOWED,
        service.decide(true, true, false, requestId, accountEmail, clientName));
  }

  @Test
  void additionalTenantIsDeclinedWhenRequestIdIsConfirmedForADifferentAccount() {
    String requestId = "req-" + System.identityHashCode(new Object());
    CheckoutPaymentRegistry.recordPaid(requestId, "owner@example.test", "Acme");

    assertEquals(Decision.PAYMENT_DECLINED,
        service.decide(true, true, false, requestId, "attacker@example.test", "Acme"));
  }

  @Test
  void additionalTenantIsDeclinedWhenRequestIdWasNeverRecordedAsPaid() {
    assertEquals(Decision.PAYMENT_DECLINED,
        service.decide(true, true, false, "req-never-paid", "buyer@example.test", "Acme"));
  }
}
