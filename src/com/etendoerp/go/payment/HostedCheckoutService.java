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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/** Small provider adapter for Stripe Checkout Sessions. Pricing is always selected server-side. */
public class HostedCheckoutService {
  /**
   * Creates a provider-hosted Checkout Session bound to the authenticated account.
   * @param accountEmail authenticated account email
   * @param clientName requested client name
   * @param origin public application origin for return URLs
   * @return checkout request id, URL, and mode
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountEmail, String clientName, String origin)
      throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) throw new IllegalStateException("Checkout is not configured");
    String requestId = UUID.randomUUID().toString();
    String form = buildSessionForm(requestId, accountEmail, clientName, origin);
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl() + "/v1/checkout/sessions").openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (OutputStream output = connection.getOutputStream()) { output.write(form.getBytes(StandardCharsets.UTF_8)); }
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) throw new IOException("Checkout provider rejected session");
    JSONObject provider = new JSONObject(response);
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", provider.optString("url", ""));
    result.put("mode", CheckoutConfiguration.mode());
    return result;
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
    String success = origin + "/upgrade?checkout=success&requestId=" + requestId;
    String cancel = origin + "/upgrade?checkout=cancelled&requestId=" + requestId;
    StringBuilder form = new StringBuilder();
    add(form, "mode", CheckoutConfiguration.mode());
    add(form, "line_items[0][price]", CheckoutConfiguration.priceId());
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
    if ("subscription".equals(CheckoutConfiguration.mode())) {
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
    form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name())).append('=').append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()));
  }

  private static String read(HttpURLConnection connection) throws IOException {
    java.io.InputStream stream = connection.getResponseCode() / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
    StringBuilder body = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line; while ((line = reader.readLine()) != null) body.append(line);
    }
    return body.toString();
  }
}
