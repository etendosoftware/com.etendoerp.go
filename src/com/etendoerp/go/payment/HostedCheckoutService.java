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
  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();

  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) throw new IllegalStateException("Checkout is not configured");
    String requestId = UUID.randomUUID().toString();
    checkoutRequestStore.recordRequested(requestId, accountId, accountEmail, clientName);
    return createProviderSession(requestId, accountEmail, clientName, origin);
  }

  public JSONObject reopenSession(String requestId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) throw new IllegalStateException("Checkout is not configured");
    return createProviderSession(requestId, accountEmail, clientName, origin);
  }

  private JSONObject createProviderSession(String requestId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    String form = buildSessionForm(requestId, accountEmail, clientName, origin);
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl()
        + "/v1/checkout/sessions").openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (OutputStream output = connection.getOutputStream()) {
      output.write(form.getBytes(StandardCharsets.UTF_8));
    }
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Checkout provider rejected session");
    }
    JSONObject provider = new JSONObject(response);
    checkoutRequestStore.recordSessionCreated(requestId, provider.optString("id", ""));
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", provider.optString("url", ""));
    result.put("mode", CheckoutConfiguration.mode());
    return result;
  }

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
    add(form, "allow_promotion_codes", "true");
    add(form, "customer_email", accountEmail);
    add(form, "metadata[account_email]", accountEmail);
    add(form, "metadata[client_name]", clientName);
    add(form, "metadata[request_id]", requestId);
    add(form, "managed_payments[enabled]", "false");
    if ("subscription".equals(CheckoutConfiguration.mode())) {
      add(form, "subscription_data[metadata][request_id]", requestId);
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
