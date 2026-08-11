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

import org.apache.commons.lang3.StringUtils;

/**
 * Decides whether an onboarding request must be paid for before any tenant is created.
 *
 * <p>The decision is deliberately a standalone unit rather than inline servlet code: it is the
 * authoritative permission check for the paid-tenant feature, so it has to be directly testable.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>Flag off → allowed. This is the pre-feature behaviour, byte for byte: no token is read and
 *       no payment is ever demanded.</li>
 *   <li>Account owns no tenant yet → allowed. A first tenant is always free.</li>
 *   <li>The request targets a tenant the account already owns → allowed. That is a resume of a
 *       partially provisioned environment, not a new one, so it must not be charged again.</li>
 *   <li>Otherwise the account is asking for an additional tenant → only a payment Stripe's webhook
 *       actually confirmed (see {@link CheckoutPaymentRegistry}) is accepted. There is no other way
 *       to pass this check: a well-shaped but unconfirmed {@code paymentToken} is declined, not
 *       approved.</li>
 * </ol>
 */
public class TenantPaywallService {

  /** Outcome of the paywall check for one onboarding request. */
  public enum Decision {
    /** Provisioning may proceed. */
    ALLOWED,
    /** An additional tenant was requested without a payment token. */
    PAYMENT_REQUIRED,
    /** A payment token was supplied but no confirmed Stripe payment matches it. */
    PAYMENT_DECLINED;

    /**
     * @return {@code true} when provisioning must be refused
     */
    public boolean isBlocked() {
      return this != ALLOWED;
    }
  }

  /**
   * Decides whether an onboarding request may proceed.
   *
   * @param upgradeFlagEnabled the {@code tenant-upgrade} flag as resolved by the backend
   * @param accountOwnsTenant whether the authenticated account already owns at least one tenant
   * @param resumingOwnedTenant whether the requested company name resolves to a tenant this account
   *     already owns, which makes the request a resume rather than a new tenant
   * @param paymentToken the {@code paymentToken} field from the onboarding payload, if any
   * @return the paywall decision
   */
  public Decision decide(boolean upgradeFlagEnabled, boolean accountOwnsTenant,
      boolean resumingOwnedTenant, String paymentToken) {
    return decide(upgradeFlagEnabled, accountOwnsTenant, resumingOwnedTenant, paymentToken, null,
        null);
  }

  /**
   * Validates a Stripe webhook-correlated payment for the authenticated account and tenant.
   *
   * @param upgradeFlagEnabled whether the tenant-upgrade flag is enabled
   * @param accountOwnsTenant whether the account already owns a tenant
   * @param resumingOwnedTenant whether this request resumes an owned tenant
   * @param paymentToken the server-generated Stripe checkout request id to correlate against
   *     {@link CheckoutPaymentRegistry}
   * @param accountEmail authenticated account email used for payment correlation
   * @param clientName requested client name used for payment correlation
   * @return the paywall decision
   */
  public Decision decide(boolean upgradeFlagEnabled, boolean accountOwnsTenant,
      boolean resumingOwnedTenant, String paymentToken, String accountEmail, String clientName) {
    if (!upgradeFlagEnabled || !accountOwnsTenant || resumingOwnedTenant) {
      return Decision.ALLOWED;
    }
    if (CheckoutPaymentRegistry.isPaidFor(paymentToken, accountEmail, clientName)) {
      return Decision.ALLOWED;
    }
    return StringUtils.isBlank(paymentToken) ? Decision.PAYMENT_REQUIRED : Decision.PAYMENT_DECLINED;
  }
}
