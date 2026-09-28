/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/** Retrieves and validates the exact Stripe Price selected for Checkout. */
public class StripePriceService {
  static final int CONNECT_TIMEOUT_MS = 5_000;
  static final int READ_TIMEOUT_MS = 10_000;
  private static final String ACTIVE_FIELD = "active";
  private static final String UNIT_AMOUNT_FIELD = "unit_amount";

  /**
   * Retrieves the configured Price and checks that its recurrence matches checkout mode.
   * @return the validated configured Stripe Price
   * @throws IOException when Stripe rejects the lookup or the Price is invalid
   * @throws JSONException when Stripe returns invalid JSON
   */
  public Price retrieveConfiguredPrice() throws IOException, JSONException {
    String priceId = StringUtils.trimToNull(CheckoutConfiguration.priceId());
    if (StringUtils.isBlank(priceId) || StringUtils.isBlank(CheckoutConfiguration.secretKey())) {
      throw new IllegalStateException("Checkout Price is not configured");
    }
    Price price = retrievePrice(priceId);
    validateConfiguredMode(price);
    return price;
  }

  /**
   * Retrieves a previously persisted Price ID for an existing purchase.
   * @param priceId previously persisted Stripe Price ID
   * @return the validated Stripe Price
   * @throws IOException when Stripe rejects the lookup or the Price is invalid
   * @throws JSONException when Stripe returns invalid JSON
   */
  public Price retrievePrice(String priceId) throws IOException, JSONException {
    priceId = StringUtils.trimToNull(priceId);
    if (StringUtils.isBlank(priceId) || StringUtils.isBlank(CheckoutConfiguration.secretKey())) {
      throw new IllegalStateException("Checkout Price is not configured");
    }
    String encodedId = URLEncoder.encode(priceId, StandardCharsets.UTF_8.name());
    HttpURLConnection connection = (HttpURLConnection) new URL(CheckoutConfiguration.apiBaseUrl()
        + "/v1/prices/" + encodedId).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
    String response = read(connection);
    if (connection.getResponseCode() / 100 != 2) {
      throw new IOException("Stripe rejected the configured Price lookup");
    }
    Price price = parsePrice(new JSONObject(response));
    if (!StringUtils.equals(priceId, price.getId())) {
      throw new IOException("Stripe returned a different Price than the configured Price");
    }
    return price;
  }

  private static void validateConfiguredMode(Price price) throws IOException {
    String mode = CheckoutConfiguration.mode();
    if ("subscription".equals(mode) && "once".equals(price.getInterval())) {
      throw new IOException("A recurring Stripe Price is required for subscription checkout");
    }
    if ("payment".equals(mode) && !"once".equals(price.getInterval())) {
      throw new IOException("A one-time Stripe Price is required for payment checkout");
    }
  }

  /** Parses the Price contract without contacting Stripe. */
  static Price parsePrice(JSONObject provider) throws JSONException, IOException {
    Object active = provider.has(ACTIVE_FIELD) && !provider.isNull(ACTIVE_FIELD)
        ? provider.get(ACTIVE_FIELD) : null;
    if (!Boolean.TRUE.equals(active)) {
      throw new IOException("The configured Stripe Price is not explicitly active");
    }
    String id = StringUtils.trimToNull(provider.optString("id", ""));
    Object amountValue = provider.has(UNIT_AMOUNT_FIELD) && !provider.isNull(UNIT_AMOUNT_FIELD)
        ? provider.get(UNIT_AMOUNT_FIELD) : null;
    if (id == null || !(amountValue instanceof Number)) {
      throw new IOException("Stripe Price is missing its id or unit amount");
    }
    Number amountNumber = (Number) amountValue;
    long amountMinor = amountNumber.longValue();
    if (amountMinor < 0 || amountNumber.doubleValue() != amountMinor) {
      throw new IOException("Stripe Price has an invalid unit amount");
    }
    String currency = StringUtils.trimToNull(provider.optString("currency", ""));
    if (currency == null || currency.length() != 3) {
      throw new IOException("Stripe Price has an invalid currency");
    }
    JSONObject recurring = provider.optJSONObject("recurring");
    String interval = "once";
    if (recurring != null) {
      interval = StringUtils.trimToNull(recurring.optString("interval", ""));
      int intervalCount = recurring.optInt("interval_count", 0);
      if (interval == null || intervalCount != 1) {
        throw new IOException("Stripe Price has an unsupported recurring interval");
      }
    }
    return new Price(id, amountMinor, currency.toUpperCase(Locale.ROOT), interval);
  }

  private static String read(HttpURLConnection connection) throws IOException {
    InputStream stream = connection.getResponseCode() / 100 == 2
        ? connection.getInputStream() : connection.getErrorStream();
    if (stream == null) return "";
    StringBuilder body = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) body.append(line);
    }
    return body.toString();
  }

  /** Provider-neutral values from one active Stripe Price. */
  public static final class Price {
    private final String id;
    private final long amountMinor;
    private final String currency;
    private final String interval;

    private Price(String id, long amountMinor, String currency, String interval) {
      this.id = id;
      this.amountMinor = amountMinor;
      this.currency = currency;
      this.interval = interval;
    }

    public String getId() { return id; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getInterval() { return interval; }
  }
}
