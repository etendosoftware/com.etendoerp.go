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
import java.time.Instant;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;

/** Provider adapter for the Stripe customer portal and live subscription display details. */
public class StripeCustomerPortalService {
  /** Bounds each provider call so a slow Stripe cannot hold a servlet thread indefinitely. */
  static final int CONNECT_TIMEOUT_MS = 5_000;
  static final int READ_TIMEOUT_MS = 10_000;

  /** Creates a short-lived Stripe Customer Portal session for the server-selected customer. */
  public JSONObject createSession(String customerId) throws IOException, JSONException {
    requireConfigured();
    String normalizedCustomerId = StringUtils.trimToNull(customerId);
    if (normalizedCustomerId == null) {
      throw new IllegalArgumentException("A Stripe customer is required");
    }
    String appBaseUrl = StringUtils.trimToNull(PublicUrlResolver.resolveConfiguredAppBaseUrl());
    if (appBaseUrl == null) {
      throw new IllegalStateException("The application base URL is not configured");
    }
    String returnUrl = appBaseUrl + (appBaseUrl.endsWith("/") ? "account" : "/account");
    String form = buildForm(normalizedCustomerId, returnUrl);
    HttpURLConnection connection = open("/v1/billing_portal/sessions", "POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (OutputStream output = connection.getOutputStream()) {
      output.write(form.getBytes(StandardCharsets.UTF_8));
    }
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Billing provider rejected portal session");
    }
    return new JSONObject(response);
  }

  /**
   * Fetches the live provider detail used by the account subscription projection.
   *
   * <p>The adapter returns a provider-neutral value so the servlet does not depend on provider
   * JSON shape.
   */
  public SubscriptionDetail retrieveSubscription(String subscriptionId)
      throws IOException, JSONException {
    requireConfigured();
    String normalizedSubscriptionId = StringUtils.trimToNull(subscriptionId);
    if (normalizedSubscriptionId == null) {
      throw new IllegalArgumentException("A Stripe subscription is required");
    }
    HttpURLConnection connection = open("/v1/subscriptions/"
        + URLEncoder.encode(normalizedSubscriptionId, StandardCharsets.UTF_8.name())
        + "?expand%5B%5D=items.data.price.product", "GET");
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Billing provider rejected subscription detail");
    }
    return SubscriptionDetail.fromProviderJson(new JSONObject(response));
  }

  static String buildForm(String customerId, String returnUrl)
      throws UnsupportedEncodingException {
    StringBuilder form = new StringBuilder();
    add(form, "customer", customerId);
    add(form, "return_url", returnUrl);
    return form.toString();
  }

  private HttpURLConnection open(String path, String method) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl()
        + path).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    return connection;
  }

  private void requireConfigured() {
    if (!CheckoutConfiguration.isConfigured()) {
      throw new IllegalStateException("Checkout is not configured");
    }
  }

  private static void add(StringBuilder form, String key, String value)
      throws UnsupportedEncodingException {
    if (form.length() > 0) {
      form.append('&');
    }
    form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name())).append('=')
        .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()));
  }

  private static String read(HttpURLConnection connection) throws IOException {
    java.io.InputStream stream = connection.getResponseCode() / 100 == 2
        ? connection.getInputStream() : connection.getErrorStream();
    if (stream == null) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        body.append(line);
      }
    }
    return body.toString();
  }

  /** Normalized live subscription detail consumed by the billing servlet. */
  public static final class SubscriptionDetail {
    private final String plan;
    private final long amountMinor;
    private final String currency;
    private final String status;
    private final String renewalAt;
    private final boolean cancelAtPeriodEnd;

    private SubscriptionDetail(String plan, long amountMinor, String currency, String status,
        String renewalAt, boolean cancelAtPeriodEnd) {
      this.plan = plan;
      this.amountMinor = amountMinor;
      this.currency = currency;
      this.status = status;
      this.renewalAt = renewalAt;
      this.cancelAtPeriodEnd = cancelAtPeriodEnd;
    }

    static SubscriptionDetail fromProviderJson(JSONObject subscription)
        throws JSONException, IOException {
      JSONObject itemsObject = subscription.optJSONObject("items");
      JSONArray items = itemsObject == null ? null : itemsObject.optJSONArray("data");
      if (items == null || items.length() == 0 || items.optJSONObject(0) == null) {
        throw new IOException("Billing provider returned no subscription items");
      }
      JSONObject item = items.getJSONObject(0);
      JSONObject price = item.optJSONObject("price");
      if (price == null) {
        throw new IOException("Billing provider returned no subscription price");
      }
      JSONObject product = price.optJSONObject("product");
      String plan = optText(price, "nickname");
      if (StringUtils.isBlank(plan) && product != null) {
        plan = optText(product, "name");
      }
      if (StringUtils.isBlank(plan)) {
        plan = optText(price, "id");
      }
      // Top level before API 2025-03-31, on the subscription item from then on.
      Instant renewal = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(subscription,
          SubscriptionLifecycleApplier.CURRENT_PERIOD_END);
      String renewalAt = renewal == null ? null : renewal.toString();
      return new SubscriptionDetail(plan, price.optLong("unit_amount", 0L),
          optText(price, "currency").toUpperCase(Locale.ROOT),
          optText(subscription, "status"), renewalAt,
          subscription.optBoolean("cancel_at_period_end", false));
    }

    /**
     * Reads a String field the way Stripe actually sends it, never as the literal word "null".
     *
     * <p>Jettison's {@code JSONObject.optString} does not fall back to the default when the key
     * maps to {@link org.codehaus.jettison.json.JSONObject#NULL} — it stringifies the null marker
     * into the literal text {@code "null"} instead. Every Stripe field that can legitimately be
     * JSON {@code null} (e.g. an unset {@code nickname}) must be read through here, not through a
     * bare {@code optString}, or the caller silently treats "null" as a real value.
     */
    private static String optText(JSONObject json, String key) {
      if (json == null || !json.has(key) || json.isNull(key)) {
        return "";
      }
      return StringUtils.trimToEmpty(json.optString(key, ""));
    }

    public String getPlan() {
      return plan;
    }

    public long getAmountMinor() {
      return amountMinor;
    }

    public String getCurrency() {
      return currency;
    }

    public String getStatus() {
      return status;
    }

    public String getRenewalAt() {
      return renewalAt;
    }

    public boolean isCancelAtPeriodEnd() {
      return cancelAtPeriodEnd;
    }
  }
}
