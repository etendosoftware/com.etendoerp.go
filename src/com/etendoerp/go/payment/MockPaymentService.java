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

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Stand-in payment provider for the paid second-tenant flow.
 *
 * <p>No money moves and no external call is made: the token's <em>shape</em> decides the outcome.
 * A token matching {@code mock-paid-<hex>} is treated as settled; anything else is rejected.
 *
 * <p>The token is client-mintable and <strong>not single-use</strong>. It is never consumed and is
 * bound to no nonce, account or amount, so the same value is accepted any number of times.
 * {@code mock-declined} is declared for contract completeness but is never transmitted — the web
 * client returns before issuing a request when a card is declined.
 *
 * <p>This is the only mock in the upgrade flow; the flag evaluation, the paywall decision and the
 * plan marker are all real. Swapping this class for a gateway client is <em>necessary but not
 * sufficient</em> to take real payments. Three gaps have to close with it: replay (one token can
 * create N tenants), check-then-act in the paywall (two concurrent onboarding calls both pass), and
 * the absence of atomicity between payment and provisioning (provisioning can fail after the gate
 * passes, leaving a captured charge with no tenant and no refund or idempotency path). See
 * {@code docs/feature-flags-and-tenant-upgrade.md} §2.
 */
public class MockPaymentService {

  /** Token shape the checkout mock issues on a successful (simulated) charge. */
  static final Pattern APPROVED_TOKEN_PATTERN = Pattern.compile("^mock-paid-[0-9a-f]+$");

  /**
   * Validates a payment token.
   *
   * @param paymentToken the token supplied in the onboarding payload; may be null or blank
   * @return {@link PaymentOutcome#MISSING_TOKEN} when absent, {@link PaymentOutcome#APPROVED} when
   *     the token matches the settled-payment shape, {@link PaymentOutcome#DECLINED} otherwise
   */
  public PaymentOutcome validate(String paymentToken) {
    String token = StringUtils.trimToNull(paymentToken);
    if (token == null) {
      return PaymentOutcome.MISSING_TOKEN;
    }
    return APPROVED_TOKEN_PATTERN.matcher(token).matches()
        ? PaymentOutcome.APPROVED
        : PaymentOutcome.DECLINED;
  }
}
