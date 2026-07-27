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
 * A token matching {@code mock-paid-<hex>} is treated as settled; anything else — including the
 * explicit {@code mock-declined} the web client sends after a simulated card decline — is rejected.
 *
 * <p>This is the only mock in the upgrade flow. The paywall gate, the flag evaluation and the plan
 * marker are all real, so replacing this class with a gateway client is the single change needed to
 * take real payments.
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
