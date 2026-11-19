/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.payment.invoicing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.ApiKeyRing;

/**
 * The payments API, at {@code /sws/go/payments/v1/*} (ETP-6322). Its caller is the ERP that
 * issues the legal invoice of every Stripe charge and mails it to the customer. Contract:
 * {@code docs/payments-api.md}.
 *
 * <pre>
 * GET   /payments?status=pending_invoice&amp;limit=50&amp;after={cursor}   a page, oldest first
 * GET   /payments/{id}                   one payment and its history
 * PATCH /payments/{id}                   {"invoiceNumber"}: the invoice issued (idempotent)
 * POST  /payments/{id}/events            {"event","step","reason"}: a step reached or failed
 * </pre>
 *
 * <p><b>Every call carries a key</b> of its own ring ({@code ETGO_PAYMENTS_API_KEYS}, see
 * {@link ApiKeyRing}): a signup key does not open it. The key's name is recorded on every event
 * the caller reports.
 *
 * <p><b>Its own servlet, reachable with no Etendo session.</b> {@code /sws/go/payments/v1/*} is a
 * longer path-prefix mapping than {@code /sws/go/*}, so the container serves it here instead of
 * {@code EtendoGoJwtServlet}, as for the signup API.
 */
public class PaymentsApiServlet extends HttpBaseServlet {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LogManager.getLogger(PaymentsApiServlet.class);

  static final String PROPERTY = "etgo.payments.api.keys";
  static final String ENV = "ETGO_PAYMENTS_API_KEYS";

  static final String PATH_PAYMENTS = "/payments";
  /** {@code /payments/{id}} and {@code /payments/{id}/events}; ids are Etendo UUIDs. */
  private static final Pattern PAYMENT_PATH =
      Pattern.compile("^/payments/([0-9A-Fa-f]{32})(/events)?$");

  static final String CODE_INVALID_API_KEY = "INVALID_API_KEY";
  static final String CODE_NOT_FOUND = "NOT_FOUND";
  static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  static final String CODE_SERVER_ERROR = "PAYMENTS_SERVER_ERROR";

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PATCH = "PATCH";

  private final transient ApiKeyRing keys;
  private final transient PaymentLedger ledger;

  /** Creates the servlet wired to the runtime ledger. */
  public PaymentsApiServlet() {
    this(new ApiKeyRing(PROPERTY, ENV), new PaymentLedger());
  }

  PaymentsApiServlet(ApiKeyRing keys, PaymentLedger ledger) {
    this.keys = keys;
    this.ledger = ledger;
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    // Every answer carries a customer's fiscal and payment data: never cache one.
    response.setHeader("Cache-Control", "no-store");
    try {
      String caller = keys.callerFor(request.getHeader(ApiKeyRing.HEADER));
      if (caller == null) {
        throw new PaymentsApiException(HttpServletResponse.SC_UNAUTHORIZED, CODE_INVALID_API_KEY,
            null, "Missing or unknown " + ApiKeyRing.HEADER);
      }
      route(StringUtils.upperCase(request.getMethod()),
          StringUtils.removeEnd(StringUtils.defaultString(request.getPathInfo()), "/"), caller,
          request, response);
    } catch (PaymentsApiException e) {
      writeError(response, e);
    } catch (RuntimeException | JSONException e) {
      // Never the request or the payment in the log: they carry fiscal data.
      log.error("Payments API request failed", e);
      writeError(response, new PaymentsApiException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          CODE_SERVER_ERROR, null, "The request failed due to a server error; try again later"));
    }
  }

  private void route(String method, String path, String caller, HttpServletRequest request,
      HttpServletResponse response) throws IOException, PaymentsApiException, JSONException {
    if (PATH_PAYMENTS.equals(path)) {
      requireMethod(METHOD_GET, method);
      handleList(request, response);
      return;
    }
    Matcher payment = PAYMENT_PATH.matcher(path);
    if (!payment.matches()) {
      throw new PaymentsApiException(HttpServletResponse.SC_NOT_FOUND, CODE_NOT_FOUND, null,
          "Not found");
    }
    String id = payment.group(1).toUpperCase();
    if (payment.group(2) != null) {
      requireMethod(METHOD_POST, method);
      JSONObject body = readBody(request);
      writeJson(response, HttpServletResponse.SC_OK,
          asSystem(() -> ledger.report(id, body, caller)));
    } else if (METHOD_PATCH.equals(method)) {
      JSONObject body = readBody(request);
      writeJson(response, HttpServletResponse.SC_OK, asSystem(() -> ledger.setInvoiceNumber(id,
          body.optString(PaymentLedger.FIELD_INVOICE_NUMBER, ""), caller)));
    } else {
      requireMethod(METHOD_GET, method);
      writeJson(response, HttpServletResponse.SC_OK, asSystem(() -> ledger.get(id)));
    }
  }

  private void handleList(HttpServletRequest request, HttpServletResponse response)
      throws IOException, PaymentsApiException, JSONException {
    PaymentLedger.Page page = asSystem(() -> ledger.list(request.getParameter("status"),
        request.getParameter("after"), request.getParameter("limit")));
    writeJson(response, HttpServletResponse.SC_OK, new JSONObject()
        .put("data", new JSONArray(page.payments()))
        .put("hasMore", page.nextCursor() != null)
        .put("nextCursor", page.nextCursor() == null ? JSONObject.NULL : page.nextCursor()));
  }

  private static void requireMethod(String expected, String actual) throws PaymentsApiException {
    if (!expected.equals(actual)) {
      throw new PaymentsApiException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          CODE_METHOD_NOT_ALLOWED, null, "Use " + expected);
    }
  }

  /** A ledger call that may be refused. */
  @FunctionalInterface
  interface LedgerCall<T> {
    /**
     * Makes the call.
     *
     * @return the call's answer
     * @throws PaymentsApiException when the ledger refuses the request
     */
    T run() throws PaymentsApiException;
  }

  /** The ledger reads every account's payments: it runs as System. */
  private static <T> T asSystem(LedgerCall<T> call) throws PaymentsApiException {
    OBContext previous = OBContext.getOBContext();
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      return call.run();
    } finally {
      OBContext.restorePreviousMode();
      OBContext.setOBContext(previous);
    }
  }

  private static JSONObject readBody(HttpServletRequest request) throws IOException,
      PaymentsApiException {
    StringBuilder text = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        text.append(line);
      }
    }
    try {
      return new JSONObject(text.toString());
    } catch (JSONException e) {
      throw PaymentLedger.invalid(null, "The body is not a JSON object");
    }
  }

  /**
   * {@code { "error": { "code", "field", "message" } }}: {@code field} only when it applies.
   * Callers branch on {@code code}; {@code message} is English, for logs.
   */
  static void writeError(HttpServletResponse response, PaymentsApiException e)
      throws IOException {
    try {
      JSONObject error = new JSONObject().put("code", e.code()).put("message", e.getMessage());
      if (e.field() != null) {
        error.put("field", e.field());
      }
      writeJson(response, e.status(), new JSONObject().put("error", error));
    } catch (JSONException jsonError) {
      log.error("Could not build the payments API error response", jsonError);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  private static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    try (PrintWriter writer = response.getWriter()) {
      writer.write(body.toString());
    }
  }
}
