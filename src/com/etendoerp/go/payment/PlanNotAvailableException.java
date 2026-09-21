/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

/**
 * Signals that the plan key the browser asked to buy names no active Subscription Plan
 * Catalog row.
 *
 * <p>Deliberately one exception for two causes — an unknown key and an inactive one. The endpoint
 * that reports it must not let a caller enumerate the plan catalog by probing keys, the same
 * non-disclosure discipline {@code handleCheckoutStatus} already applies to request ids, so the
 * two are indistinguishable from the outside and are therefore indistinguishable here too.
 *
 * <p>Distinct from {@link IllegalStateException}, which the checkout path uses for "there is
 * nothing sellable configured" and which answers {@code 503 CHECKOUT_NOT_CONFIGURED}: that is a
 * deployment problem the buyer cannot fix, whereas this is a rejected request.
 */
public class PlanNotAvailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a rejection naming the key that was asked for.
   *
   * @param planKey the plan key the request carried; only ever logged, never echoed to the caller
   */
  public PlanNotAvailableException(String planKey) {
    super("No active plan is available under the key '" + planKey + "'");
  }
}
