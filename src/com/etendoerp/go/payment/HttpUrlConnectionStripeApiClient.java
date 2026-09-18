/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The production {@link StripeApiClient}: plain {@link HttpURLConnection}, the same mechanics the
 * Checkout Session call already uses, with no new dependency.
 *
 * <p>Its whole job is to decide which outcomes are answers and which are non-events. A 2xx or a
 * 4xx is an answer and comes back as a {@link StripeResponse}; a timeout, a DNS or TLS failure, a
 * 5xx and a 429 are not answers and come back as a {@link StripeTransportException}. See that
 * class for why the distinction is load-bearing.
 */
public class HttpUrlConnectionStripeApiClient implements StripeApiClient {

  private static final Logger log = LogManager.getLogger(HttpUrlConnectionStripeApiClient.class);

  private static final int TOO_MANY_REQUESTS = 429;

  @Override
  public StripeResponse get(String path) throws StripeTransportException {
    return execute("GET", path, null);
  }

  @Override
  public StripeResponse postForm(String path, String formBody) throws StripeTransportException {
    return execute("POST", path, formBody == null ? "" : formBody);
  }

  private StripeResponse execute(String method, String path, String formBody)
      throws StripeTransportException {
    String url = CheckoutConfiguration.apiBaseUrl() + path;
    HttpURLConnection connection = open(url, method);
    try {
      if (formBody != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream output = connection.getOutputStream()) {
          output.write(formBody.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = connection.getResponseCode();
      String body = read(connection, status);
      if (status >= 500 || status == TOO_MANY_REQUESTS) {
        // Not a verdict about the request: the provider is telling us to come back later. Raised
        // as a transport failure so callers never mistake it for "your configuration is wrong".
        log.warn("Provider answered {} for {} {} — treating it as unreachable", status, method,
            path);
        throw new StripeTransportException(
            "Payment provider answered " + status + " for " + method + " " + path);
      }
      return new StripeResponse(status, body);
    } catch (IOException e) {
      throw asTransportFailure(method, path, e);
    } finally {
      connection.disconnect();
    }
  }

  private HttpURLConnection open(String url, String method) throws StripeTransportException {
    try {
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + CheckoutConfiguration.secretKey());
      // HttpURLConnection has NO timeout by default: 0 means "wait forever". This call can run on
      // a Classic window save path (the plan price derivation observer), so an un-timed-out call
      // against an unreachable provider would hang the request thread — and therefore the user's
      // browser — indefinitely, with nothing in any log. A bounded wait that fails with
      // ETGO_PlanPriceUnreachable is always better than a save that never returns.
      connection.setConnectTimeout(CheckoutConfiguration.connectTimeoutMs());
      connection.setReadTimeout(CheckoutConfiguration.readTimeoutMs());
      return connection;
    } catch (MalformedURLException e) {
      throw new StripeTransportException("Invalid payment provider URL: " + url, e);
    } catch (IOException e) {
      throw new StripeTransportException("Cannot open a connection to " + url, e);
    }
  }

  private static String read(HttpURLConnection connection, int status) throws IOException {
    InputStream stream = status / 100 == 2 ? connection.getInputStream()
        : connection.getErrorStream();
    if (stream == null) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        body.append(line);
      }
    }
    return body.toString();
  }

  private static StripeTransportException asTransportFailure(String method, String path,
      IOException e) {
    if (e instanceof StripeTransportException) {
      return (StripeTransportException) e;
    }
    log.warn("Payment provider call {} {} did not complete", method, path, e);
    return new StripeTransportException(
        "Payment provider call " + method + " " + path + " did not complete", e);
  }
}
