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
 *   <li>Converting an existing environment to productive is a purchase, so it never takes a free
 *       path — it falls straight through to the payment check below.</li>
 *   <li>Account owns no environment yet → allowed. A first environment is always free.</li>
 *   <li>The request targets an environment the account already owns → allowed. That is a resume of a
 *       partially provisioned environment, not a new one, so it must not be charged again.</li>
 *   <li>Otherwise the account is asking for an additional environment → only a payment Stripe's
 *       webhook actually confirmed (see {@link CheckoutPaymentRegistry}) is accepted. There is no
 *       other way to pass this check: a well-shaped but unconfirmed {@code paymentToken} is
 *       declined, not approved.</li>
 * </ol>
 *
 * <p><strong>There is no feature flag.</strong> The paid-environment capability is permanent: it
 * cannot be switched off, and no configuration can change the outcome of an evaluation. ETP-4966 is
 * why. While it was gated, the web client and the backend resolved the same flag key through
 * different control planes — ConfigCat in the browser, local properties on the server — and the
 * server's copy was unset in every deployed environment. The browser therefore offered a checkout
 * the server did not believe in: accounts were charged, and the paywall short-circuited to
 * {@code ALLOWED} without ever reading their payment. An unconditional capability cannot disagree
 * with itself.
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
   * Outcome of the paid-environment evaluation: whether the request may provision, and whether the
   * resulting environment is productive.
   *
   * <p>The two are separate answers on purpose. Inferring the plan from the decision is what shipped
   * ETP-4966: a request that was allowed for a reason unrelated to payment read back as unpaid, so a
   * charged account received a demo environment.
   */
  public static final class Outcome {

    private final Decision decision;
    private final boolean productive;

    Outcome(Decision decision, boolean productive) {
      this.decision = decision;
      this.productive = productive;
    }

    /**
     * @return whether provisioning may proceed
     */
    public Decision getDecision() {
      return decision;
    }

    /**
     * @return {@code true} when the environment this request provisions must be marked productive
     */
    public boolean isProductive() {
      return productive;
    }
  }

  /**
   * Evaluates one onboarding request: whether it may provision, and whether what it provisions is
   * productive.
   *
   * <p>The plan is derived from the payment and from nothing else. A webhook-confirmed payment means
   * the account was charged, so the environment it paid for must come back productive — including in
   * the cases where the paywall would have let the request through for free anyway (a first
   * environment, or a name that resolves to one the account already owns). Deriving the plan from
   * the decision instead is what made ETP-4966 invisible: the request was allowed, no payment was
   * ever read, and the resulting environment looked exactly like an unpaid one.
   *
   * @param accountOwnsEnvironment whether the account already owns at least one environment
   * @param resumingOwnedEnvironment whether the requested name resolves to an environment this
   *     account already owns, which makes the request a resume rather than a new environment
   * @param convertingToProductive whether the request converts an existing environment
   *     ({@code upgradeAction=convert-demo}) rather than creating one
   * @param paymentToken the server-generated Stripe checkout request id to correlate against
   *     {@link CheckoutPaymentRegistry}
   * @param accountEmail authenticated account email used for payment correlation
   * @param clientName requested environment name used for payment correlation
   * @return the evaluation outcome
   */
  public Outcome evaluate(boolean accountOwnsEnvironment, boolean resumingOwnedEnvironment,
      boolean convertingToProductive, String paymentToken, String accountEmail, String clientName) {
    boolean confirmedPayment = CheckoutPaymentRegistry.isPaidFor(paymentToken, accountEmail,
        clientName);
    Decision decision = decide(accountOwnsEnvironment, resumingOwnedEnvironment,
        convertingToProductive, paymentToken, confirmedPayment);
    // A refused request provisions nothing, so there is nothing to promote. Guarding on the
    // decision here is what keeps a confirmed-but-mismatched payment from granting the paid plan.
    return new Outcome(decision, !decision.isBlocked() && confirmedPayment);
  }

  /**
   * Applies the rules documented on this class, in order.
   *
   * @param accountOwnsEnvironment whether the account already owns at least one environment
   * @param resumingOwnedEnvironment whether this request resumes an owned environment
   * @param convertingToProductive whether this request converts an existing environment
   * @param paymentToken the payment token from the onboarding payload, if any
   * @param confirmedPayment whether the token correlates to a webhook-confirmed payment
   * @return the paywall decision
   */
  private static Decision decide(boolean accountOwnsEnvironment, boolean resumingOwnedEnvironment,
      boolean convertingToProductive, String paymentToken, boolean confirmedPayment) {
    if (!convertingToProductive) {
      // Conversion is a paid state transition, so it deliberately skips both free paths: without
      // this guard it would look like an ordinary resume of an environment the account owns and
      // pass for free.
      if (!accountOwnsEnvironment || resumingOwnedEnvironment) {
        return Decision.ALLOWED;
      }
    }
    if (confirmedPayment) {
      return Decision.ALLOWED;
    }
    return StringUtils.isBlank(paymentToken) ? Decision.PAYMENT_REQUIRED : Decision.PAYMENT_DECLINED;
  }
}
