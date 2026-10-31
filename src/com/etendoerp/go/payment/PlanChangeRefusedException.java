/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

/**
 * ETP-5053 — a plan change the service refuses before or after asking the provider, with a stable
 * machine-readable code the API and the Classic action translate for their users. Never an
 * unhandled provider error: every refusal the ticket lists has a code here.
 */
public class PlanChangeRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The tenant has no open subscription, or its row names no provider subscription. */
  public static final String NO_SUBSCRIPTION = "NO_SUBSCRIPTION";
  /** The requested plan key names no purchasable plan. */
  public static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";
  /** No active Allowed plan changes row offers this transition (same plan included). */
  public static final String PLAN_CHANGE_NOT_ALLOWED = "PLAN_CHANGE_NOT_ALLOWED";
  /** The subscription is past due or canceled; only a current subscription changes plan. */
  public static final String SUBSCRIPTION_NOT_CURRENT = "SUBSCRIPTION_NOT_CURRENT";
  /** The provider is not configured, or the target plan carries no provider price. */
  public static final String NOT_CONFIGURED = "CHECKOUT_NOT_CONFIGURED";
  /** The provider answered, but refused the change (a 4xx) or sent an unusable object. */
  public static final String PROVIDER_REJECTED = "BILLING_PROVIDER_ERROR";

  private final String code;

  /**
   * A refusal with its stable code and the reason, for logs and the operator.
   *
   * @param code one of this class's codes
   * @param message what was refused and why, for logs and the operator
   */
  public PlanChangeRefusedException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** @return the stable refusal code */
  public String code() {
    return code;
  }
}
