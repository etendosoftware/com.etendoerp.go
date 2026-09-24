/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

/**
 * The whole surface this module needs from the payment provider's HTTP API.
 *
 * <p>Deliberately small, for the same reason
 * {@link CheckoutWebhookProcessor.EventStore} is one method: a test double for it is a handful of
 * lines with no HTTP, no mocking framework and no network, so every branch of the code that
 * consumes provider answers stays testable. The base URL, the credentials and the timeouts belong
 * to the implementation, not to this contract — a caller names a path and gets back either an
 * answer or a {@link StripeTransportException}.
 *
 * <p>Note what is <b>not</b> here: nothing that reports a failure by returning null or by
 * throwing a generic {@code IOException}. A completed call always yields a
 * {@link StripeResponse}, whatever its status; only a call that did not complete throws.
 */
public interface StripeApiClient {

  /**
   * Performs a GET against the provider.
   *
   * @param path path relative to the provider API base URL, starting with a slash, e.g.
   *     {@code /v1/prices/price_123}
   * @return the completed answer, whatever its HTTP status
   * @throws StripeTransportException when the call did not complete (timeout, DNS, TLS, 5xx, 429)
   */
  StripeResponse get(String path) throws StripeTransportException;

  /**
   * Performs a form-encoded POST against the provider.
   *
   * @param path path relative to the provider API base URL, starting with a slash
   * @param formBody already URL-encoded {@code application/x-www-form-urlencoded} body
   * @return the completed answer, whatever its HTTP status
   * @throws StripeTransportException when the call did not complete (timeout, DNS, TLS, 5xx, 429)
   */
  StripeResponse postForm(String path, String formBody) throws StripeTransportException;

  /**
   * Performs a form-encoded POST carrying a Stripe {@code Idempotency-Key} header.
   *
   * <p>The checkout path needs it: a retried session creation must be recognised by the provider
   * as the same request, or a lost response followed by a retry creates a second payable session.
   * The default refuses a key rather than dropping it, because an implementation that silently
   * ignored the header would turn every retry into a duplicate charge without any error. A
   * {@code null} key is a plain POST.
   *
   * @param path path relative to the provider API base URL, starting with a slash
   * @param formBody already URL-encoded {@code application/x-www-form-urlencoded} body
   * @param idempotencyKey value of the {@code Idempotency-Key} header, or null for none
   * @return the completed answer, whatever its HTTP status
   * @throws StripeTransportException when the call did not complete (timeout, DNS, TLS, 5xx, 429)
   */
  default StripeResponse postForm(String path, String formBody, String idempotencyKey)
      throws StripeTransportException {
    if (idempotencyKey == null) {
      return postForm(path, formBody);
    }
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support idempotent POST requests");
  }
}
