/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/** Small provider adapter for Stripe Checkout Sessions. Pricing is always selected server-side. */
public class HostedCheckoutService {
  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();

  /** A persisted purchase cannot be resumed when its original Stripe Price ID is missing. */
  public static final class OriginalPriceUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private OriginalPriceUnavailableException() {
      super("The original checkout price is unavailable");
    }
  }

  /**
   * Creates a provider-hosted Checkout Session bound to the authenticated account.
   * @param accountId authenticated account id, correlated on the durable request row
   * @param accountEmail authenticated account email
   * @param clientName requested client name
   * @param origin public application origin for return URLs
   * @return checkout request id, URL, and mode
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String demoClientId, String origin) throws IOException, JSONException {
    return createSession(accountId, accountEmail, clientName, origin, demoClientId, false, false);
  }

  /**
   * Persists request-specific local intent before contacting the payment provider.
   * @param accountId authenticated account id, correlated on the durable request row
   * @param accountEmail authenticated account email
   * @param clientName requested client name
   * @param origin public application origin for return URLs
   * @param beforeProvider callback invoked with the request id before contacting the provider
   * @return checkout request id, URL, and mode
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, Consumer<String> beforeProvider) throws IOException, JSONException {
    return createSession(accountId, accountEmail, clientName, origin, null, false, false,
        beforeProvider);
  }

  /** Creates a checkout bound to an immutable demo source and transfer selection. */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, String demoClientId, boolean transferProducts, boolean transferContacts)
      throws IOException, JSONException {
    return createSession(accountId, accountEmail, clientName, origin, demoClientId,
        transferProducts, transferContacts, requestId -> { });
  }

  /**
   * Creates a checkout bound to an immutable demo source, transfer selection and Stripe Price,
   * running {@code beforeProvider} with the request id once the row is committed and before the
   * provider is contacted.
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, String demoClientId, boolean transferProducts, boolean transferContacts,
      Consumer<String> beforeProvider) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) throw new IllegalStateException("Checkout is not configured");
    StripePriceService.Price price = new StripePriceService().retrieveConfiguredPrice();
    String requestId = UUID.randomUUID().toString();
    // Recorded and committed BEFORE the provider is contacted. A crash during the call below would
    // otherwise leave a session at Stripe that nothing on this side can name, and therefore that no
    // reconciliation could ever find. The row is deliberately not rolled back when the call fails:
    // it is the evidence that someone tried to buy something, and it is always safe to expire
    // because the checkoutUrl only reaches the browser once this method returns.
    checkoutRequestStore.recordRequested(requestId, accountId, accountEmail, clientName,
        demoClientId, true, transferProducts, transferContacts, price.getId());
    beforeProvider.accept(requestId);
    return createProviderSession(requestId, accountEmail, clientName, origin, price,
        initialIdempotencyKey(requestId), null);
  }

  /** Compatibility entry point for checkouts that do not select a demo environment. */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    return createSession(accountId, accountEmail, clientName, origin, null, false, false);
  }

  /**
   * Reopens the provider checkout for an existing unpaid request.
   *
   * <p>The durable request is the idempotency boundary for the purchase. A browser refresh or a
   * provider redirect can leave that request in CREATED while the checkout URL is no longer in
   * the browser. Reusing the request id lets the buyer continue without creating a second purchase
   * row or a second webhook correlation key.
   *
   * @param requestId existing checkout request id
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @return checkout request id, URL, and mode
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject reopenSession(String requestId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) throw new IllegalStateException("Checkout is not configured");
    String sessionId = checkoutRequestStore.findStripeSessionId(requestId);
    if (sessionId != null) {
      JSONObject existing = retrieveSession(sessionId);
      if (!sessionId.equals(existing.optString("id", ""))) {
        throw new IOException("Stripe returned a different existing checkout session");
      }
      String sessionStatus = existing.optString("status", "");
      if ("open".equals(sessionStatus)) {
        String originalMode = existing.optString("mode", "");
        if (originalMode.isEmpty()) {
          throw new IOException("Stripe returned an existing session without its checkout mode");
        }
        return buildResult(requestId, existing.optString("url", ""),
            checkoutRequestStore.findStripePriceId(requestId), originalMode);
      }
      if ("complete".equals(sessionStatus)) {
        return buildCompletedResult(requestId, existing);
      }
      if (!"expired".equals(sessionStatus)) {
        throw new IllegalStateException("The existing checkout session is not reopenable");
      }
    }

    String priceId = checkoutRequestStore.findStripePriceId(requestId);
    if (priceId == null) {
      throw new OriginalPriceUnavailableException();
    }
    StripePriceService.Price price = new StripePriceService().retrievePrice(priceId);
    return createProviderSession(requestId, accountEmail, clientName, origin, price,
        sessionId == null ? initialIdempotencyKey(requestId)
            : replacementIdempotencyKey(requestId, sessionId), sessionId);
  }

  private JSONObject buildCompletedResult(String requestId, JSONObject session)
      throws JSONException {
    String paymentStatus = session.optString("payment_status", "");
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("providerStatus", "complete");
    result.put("paymentStatus", paymentStatus);
    result.put("paymentComplete", "paid".equals(paymentStatus)
        || "no_payment_required".equals(paymentStatus));
    result.put("stripeCustomer", session.optString("customer", ""));
    result.put("stripeSubscription", session.optString("subscription", ""));
    return result;
  }

  private JSONObject createProviderSession(String requestId, String accountEmail, String clientName,
      String origin, StripePriceService.Price price, String idempotencyKey,
      String expectedSessionId) throws IOException, JSONException {
    String mode = "once".equals(price.getInterval()) ? "payment" : "subscription";
    String form = buildSessionForm(requestId, accountEmail, clientName, origin, price.getId(), mode);
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl()
        + "/v1/checkout/sessions").openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    connection.setRequestProperty("Idempotency-Key", idempotencyKey);
    try (OutputStream output = connection.getOutputStream()) {
      output.write(form.getBytes(StandardCharsets.UTF_8));
    }
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Checkout provider rejected session");
    }
    JSONObject provider = new JSONObject(response);
    String providerSessionId = provider.optString("id", "");
    String providerUrl = provider.optString("url", "");
    if (providerSessionId.isEmpty() || providerUrl.isEmpty()) {
      throw new IOException("Checkout provider returned an incomplete session");
    }
    // The provider session id is the reconciliation anchor for an abandoned or lost checkout, and
    // this response is the only place it appears. Recorded before the URL is handed back.
    checkoutRequestStore.recordSessionCreated(requestId, expectedSessionId, providerSessionId);
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", providerUrl);
    result.put("mode", mode);
    result.put("priceId", price.getId());
    return result;
  }

  private JSONObject retrieveSession(String sessionId) throws IOException, JSONException {
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl()
        + "/v1/checkout/sessions/" + sessionId).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(StripePriceService.CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(StripePriceService.READ_TIMEOUT_MS);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Could not retrieve existing checkout session");
    }
    return new JSONObject(response);
  }

  private JSONObject buildResult(String requestId, String checkoutUrl, String priceId, String mode)
      throws JSONException {
    if (checkoutUrl == null || checkoutUrl.isEmpty()) {
      throw new IllegalStateException("The checkout provider returned no session URL");
    }
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", checkoutUrl);
    result.put("mode", mode);
    if (priceId != null) result.put("priceId", priceId);
    return result;
  }

  static String initialIdempotencyKey(String requestId) {
    return "checkout:" + requestId + ":initial";
  }

  static String replacementIdempotencyKey(String requestId, String expiredSessionId) {
    return "checkout:" + requestId + ":after:" + expiredSessionId;
  }

  /**
   * Builds the form-encoded Checkout Session body.
   *
   * <p>Package-visible so the contract sent to Stripe is directly assertable: this body is the whole
   * agreement with the provider, and every field in it is a product decision (what is charged, what
   * the webhook can correlate, what the buyer may do at the till). A field silently dropped here is
   * invisible until someone reaches the hosted page.
   *
   * @param requestId server-generated checkout request id, correlated by the webhook
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @return the form-encoded request body
   * @throws UnsupportedEncodingException never in practice; UTF-8 is always available
   */
  static String buildSessionForm(String requestId, String accountEmail, String clientName,
      String origin) throws UnsupportedEncodingException {
    return buildSessionForm(requestId, accountEmail, clientName, origin,
        CheckoutConfiguration.priceId(), CheckoutConfiguration.mode());
  }

  static String buildSessionForm(String requestId, String accountEmail, String clientName,
      String origin, String priceId) throws UnsupportedEncodingException {
    return buildSessionForm(requestId, accountEmail, clientName, origin, priceId,
        CheckoutConfiguration.mode());
  }

  static String buildSessionForm(String requestId, String accountEmail, String clientName,
      String origin, String priceId, String mode) throws UnsupportedEncodingException {
    String success = origin + "/upgrade?checkout=success&requestId=" + requestId;
    String cancel = origin + "/upgrade?checkout=cancelled&requestId=" + requestId;
    StringBuilder form = new StringBuilder();
    add(form, "mode", mode);
    add(form, "line_items[0][price]", priceId);
    add(form, "line_items[0][quantity]", "1");
    add(form, "success_url", success);
    add(form, "cancel_url", cancel);
    add(form, "client_reference_id", requestId);
    // Show the "Add promotion code" field. Off by default in Stripe, which makes any coupon the
    // business creates unreachable from the hosted page. Mutually exclusive with a `discounts`
    // parameter — do not add one here without removing this.
    add(form, "allow_promotion_codes", "true");
    // Bind Checkout to the authenticated Etendo account; the browser cannot override this email.
    add(form, "customer_email", accountEmail);
    add(form, "metadata[account_email]", accountEmail);
    add(form, "metadata[client_name]", clientName);
    add(form, "metadata[request_id]", requestId);
    // The sandbox product is not configured for Stripe Managed Payments. Keep the
    // Checkout contract explicit until Product selects an eligible tax code.
    add(form, "managed_payments[enabled]", "false");
    if ("subscription".equals(mode)) {
      add(form, "subscription_data[metadata][request_id]", requestId);
      // Skip the card when a promotion code brings the total to 0, so a 100%-off code does not make
      // the buyer enter card details for a charge that will never happen. Stripe evaluates this per
      // session against the amount due, so the ordinary paid path still collects a card.
      //
      // Two constraints, both load-bearing:
      //  - Stripe accepts this parameter in `subscription` mode ONLY, which is why it lives inside
      //    this branch. Sent in `payment` mode it fails the whole session, not just the field.
      //  - It is only safe for coupons with `duration=forever`. A `once` or `repeating` coupon
      //    leaves no payment method on file, so the first invoice after the discount ends has
      //    nothing to charge. Coupons meant to make an environment free must be created `forever`.
      add(form, "payment_method_collection", "if_required");
    }
    return form.toString();
  }

  private static void add(StringBuilder form, String key, String value)
      throws UnsupportedEncodingException {
    if (form.length() > 0) form.append('&');
    form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name())).append('=')
        .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()));
  }

  private static String read(HttpURLConnection connection) throws IOException {
    java.io.InputStream stream = connection.getResponseCode() / 100 == 2
        ? connection.getInputStream() : connection.getErrorStream();
    StringBuilder body = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) body.append(line);
    }
    return body.toString();
  }
}
