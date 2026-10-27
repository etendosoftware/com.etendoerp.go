/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.io.IOException;

/**
 * Signals that a call to the payment provider <b>did not complete</b>: a connect or read timeout,
 * a DNS failure, a TLS failure, or a response the provider itself says is not an answer — 5xx and
 * 429, which are retryable rather than verdicts.
 *
 * <p>This split is the entire mechanism that makes "Stripe is unreachable" distinguishable from
 * "that price id does not exist". A call that completed with a 4xx returns a
 * {@link StripeResponse} and never throws, so the caller can read the provider's verdict; a call
 * that never produced a verdict throws this, so the caller can say so instead of blaming the
 * configuration the user just typed.
 *
 * <p>It extends {@link IOException} because that is what it is — the transport failed — and
 * because it keeps the checked-exception discipline the rest of the provider adapter already
 * uses.
 */
public class StripeTransportException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a transport failure with a human-readable reason.
   *
   * @param message what was attempted and how it failed
   */
  public StripeTransportException(String message) {
    super(message);
  }

  /**
   * Creates a transport failure wrapping the underlying I/O failure.
   *
   * @param message what was attempted and how it failed
   * @param cause the original transport failure, kept so the stack trace names the real socket
   *     or TLS error
   */
  public StripeTransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
