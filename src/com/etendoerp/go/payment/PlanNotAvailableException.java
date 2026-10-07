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

  /** Longest prefix of a rejected key kept in the message; real plan keys are far shorter. */
  static final int MAX_LOGGED_KEY_LENGTH = 64;

  /**
   * Creates a rejection naming the key that was asked for.
   *
   * <p>The key is untrusted browser input and the message is logged, so it goes through
   * {@link #loggable(String)} first: a key carrying line breaks cannot forge log lines, and an
   * oversized one cannot flood the log.
   *
   * @param planKey the plan key the request carried; only ever logged, never echoed to the caller
   */
  public PlanNotAvailableException(String planKey) {
    super("No active plan is available under the key '" + loggable(planKey) + "'");
  }

  /**
   * Makes an untrusted plan key safe to log: every control or line/paragraph separator character
   * becomes {@code ?}, and anything past {@value #MAX_LOGGED_KEY_LENGTH} characters is cut and
   * replaced by the original length.
   *
   * @param planKey the raw key, may be null
   * @return a single-line, bounded rendering of the key
   */
  static String loggable(String planKey) {
    if (planKey == null) {
      return "<none>";
    }
    String singleLine = planKey.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "?");
    if (singleLine.length() <= MAX_LOGGED_KEY_LENGTH) {
      return singleLine;
    }
    return singleLine.substring(0, MAX_LOGGED_KEY_LENGTH) + "...(" + planKey.length() + " chars)";
  }
}
