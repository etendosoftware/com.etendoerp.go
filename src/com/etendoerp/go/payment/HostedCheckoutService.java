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
    String success = origin + "/upgrade?checkout=success&requestId=" + requestId;
    String cancel = origin + "/upgrade?checkout=cancelled&requestId=" + requestId;
    StringBuilder form = new StringBuilder();
    add(form, "mode", CheckoutConfiguration.mode());
    add(form, "line_items[0][price]", CheckoutConfiguration.priceId());
    add(form, "line_items[0][quantity]", "1");
    add(form, "success_url", success);
    add(form, "cancel_url", cancel);
    add(form, "client_reference_id", requestId);
    // Bind Checkout to the authenticated Etendo account; the browser cannot override this email.
    add(form, "customer_email", accountEmail);
    add(form, "metadata[account_email]", accountEmail);
    add(form, "metadata[client_name]", clientName);
    add(form, "metadata[request_id]", requestId);
    // The sandbox product is not configured for Stripe Managed Payments. Keep the
    // Checkout contract explicit until Product selects an eligible tax code.
    add(form, "managed_payments[enabled]", "false");
    if ("subscription".equals(CheckoutConfiguration.mode())) add(form, "subscription_data[metadata][request_id]", requestId);
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl() + "/v1/checkout/sessions").openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (OutputStream output = connection.getOutputStream()) { output.write(form.toString().getBytes(StandardCharsets.UTF_8)); }
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) throw new IOException("Checkout provider rejected session");
    JSONObject provider = new JSONObject(response);
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", provider.optString("url", ""));
    result.put("mode", CheckoutConfiguration.mode());
    return result;
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
