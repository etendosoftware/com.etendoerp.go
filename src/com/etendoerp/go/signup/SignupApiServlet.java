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

package com.etendoerp.go.signup;

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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.rest.AccountRegistrationService;
import com.etendoerp.go.rest.SignupAccountService;

/**
 * The signup API, at {@code /sws/go/signup/v1/*} (ETP-5847). Its callers are servers — the
 * marketing site's backend, the CRM, an agent — each with its own key. Contract:
 * {@code docs/signup-api.md}.
 *
 * <pre>
 * GET  /config                       what an account may be created with
 * POST /accounts                     create a passwordless account and its demo environment
 * GET  /accounts/{id}                the account's signup state
 * POST /accounts/{id}/login-link     a fresh one-time login link, optionally mailed
 * POST /accounts/{id}/retry          run a failed environment creation again
 * </pre>
 *
 * <p><b>Every call carries a key</b> ({@link SignupApiKeys}), so the API is never called from a
 * browser and has no CORS: a key in a page would be a key handed to anyone who opens it.
 *
 * <p><b>Its own servlet, reachable with no Etendo session.</b> {@code /sws/go/signup/v1/*} is a
 * longer path-prefix mapping than {@code /sws/go/*}, so the container serves it here instead of
 * {@code EtendoGoJwtServlet} — the same mechanism {@code PortalServlet} documents.
 *
 * <p>Out of this version, by decision: captcha, rate limiting, and hiding whether an address is
 * already registered (the caller is told, {@code EMAIL_ALREADY_REGISTERED}).
 */
public class SignupApiServlet extends HttpBaseServlet {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LogManager.getLogger(SignupApiServlet.class);

  static final String PATH_CONFIG = "/config";
  static final String PATH_ACCOUNTS = "/accounts";
  /** {@code /accounts/{id}} and {@code /accounts/{id}/<action>}; ids are Etendo UUIDs. */
  private static final Pattern ACCOUNT_PATH =
      Pattern.compile("^/accounts/([0-9A-Fa-f]{32})(/login-link|/retry)?$");
  private static final String ACTION_LOGIN_LINK = "/login-link";
  private static final String ACTION_RETRY = "/retry";

  static final String CODE_INVALID_API_KEY = "INVALID_API_KEY";
  static final String CODE_NOT_FOUND = "NOT_FOUND";
  static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  static final String CODE_RESERVED_COMPANY_NAME = "RESERVED_COMPANY_NAME";
  static final String CODE_EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
  static final String CODE_EMAIL_PENDING_VERIFICATION = "EMAIL_PENDING_VERIFICATION";
  static final String CODE_ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
  static final String CODE_ENVIRONMENT_NOT_READY = "ENVIRONMENT_NOT_READY";
  static final String CODE_RETRY_NOT_ALLOWED = "RETRY_NOT_ALLOWED";
  static final String CODE_LOGIN_LINK_UNAVAILABLE = "LOGIN_LINK_UNAVAILABLE";
  static final String CODE_SIGNUP_SERVER_ERROR = "SIGNUP_SERVER_ERROR";

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String FIELD_ACCOUNT_ID = "accountId";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_STATUS = "status";

  private final transient AccountRegistrationService registration;
  private final transient SignupAccountService signups;

  /** Creates the servlet wired to the runtime services. */
  public SignupApiServlet() {
    this(new AccountRegistrationService(), new SignupAccountService());
  }

  SignupApiServlet(AccountRegistrationService registration, SignupAccountService signups) {
    this.registration = registration;
    this.signups = signups;
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    // Every answer is about one account: never cache one.
    response.setHeader("Cache-Control", "no-store");
    try {
      String channel = SignupApiKeys.channelFor(request.getHeader(SignupApiKeys.HEADER));
      if (channel == null) {
        throw new SignupException(HttpServletResponse.SC_UNAUTHORIZED, CODE_INVALID_API_KEY,
            null, "Missing or unknown " + SignupApiKeys.HEADER);
      }
      route(StringUtils.upperCase(request.getMethod()),
          StringUtils.removeEnd(StringUtils.defaultString(request.getPathInfo()), "/"), channel,
          request, response);
    } catch (SignupException e) {
      writeError(response, e);
    } catch (RuntimeException | JSONException e) {
      // Never the request body in the log: it carries personal data.
      log.error("Signup request failed", e);
      writeError(response, new SignupException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          CODE_SIGNUP_SERVER_ERROR, null, "Signup failed due to a server error; try again later"));
    }
  }

  private void route(String method, String path, String channel, HttpServletRequest request,
      HttpServletResponse response) throws IOException, SignupException, JSONException {
    if (PATH_CONFIG.equals(path)) {
      requireMethod(METHOD_GET, method);
      writeJson(response, HttpServletResponse.SC_OK, SignupOptions.toJson());
      return;
    }
    if (PATH_ACCOUNTS.equals(path)) {
      requireMethod(METHOD_POST, method);
      handleCreate(channel, request, response);
      return;
    }
    Matcher account = ACCOUNT_PATH.matcher(path);
    if (!account.matches()) {
      throw new SignupException(HttpServletResponse.SC_NOT_FOUND, CODE_NOT_FOUND, null,
          "Not found");
    }
    String accountId = account.group(1).toUpperCase();
    String action = StringUtils.defaultString(account.group(2));
    if (action.isEmpty()) {
      requireMethod(METHOD_GET, method);
      handleStatus(accountId, response);
    } else if (ACTION_LOGIN_LINK.equals(action)) {
      requireMethod(METHOD_POST, method);
      handleLoginLink(accountId, request, response);
    } else if (ACTION_RETRY.equals(action)) {
      requireMethod(METHOD_POST, method);
      handleRetry(accountId, response);
    }
  }

  private static void requireMethod(String expected, String actual) throws SignupException {
    if (!expected.equals(actual)) {
      throw new SignupException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          CODE_METHOD_NOT_ALLOWED, null, "Use " + expected);
    }
  }

  /**
   * {@code POST /accounts}. Validates, refuses an address already in use (telling which way),
   * creates the account and answers at once: the environment is created in the background.
   */
  private void handleCreate(String channel, HttpServletRequest request,
      HttpServletResponse response) throws IOException, SignupException, JSONException {
    SignupRequest signup = SignupRequest.parse(readBody(request));
    runAsSystem(() -> {
      if (registration.isReservedCompanyName(signup.companyName())) {
        throw SignupRequest.badRequest(CODE_RESERVED_COMPANY_NAME, SignupRequest.FIELD_COMPANY_NAME,
            "This company name is reserved. Choose a different name.");
      }
      refuseAddressInUse(registration.emailStatus(signup.email()));
    });
    String accountId = signups.create(signup.toNewAccount(channel));
    writeJson(response, HttpServletResponse.SC_ACCEPTED, new JSONObject()
        .put(FIELD_ACCOUNT_ID, accountId)
        .put(FIELD_EMAIL, signup.email())
        .put(FIELD_STATUS, "provisioning"));
  }

  private static void refuseAddressInUse(AccountRegistrationService.EmailStatus status)
      throws SignupException {
    if (status == AccountRegistrationService.EmailStatus.PENDING_VERIFICATION) {
      throw conflict(CODE_EMAIL_PENDING_VERIFICATION, FIELD_EMAIL,
          "This email is registered and waiting for its confirmation");
    }
    if (status == AccountRegistrationService.EmailStatus.REGISTERED) {
      throw conflict(CODE_EMAIL_ALREADY_REGISTERED, FIELD_EMAIL, "Email already registered");
    }
  }

  /** {@code GET /accounts/{id}}: {@code provisioning}, {@code ready} or {@code failed}. */
  private void handleStatus(String accountId, HttpServletResponse response)
      throws IOException, SignupException, JSONException {
    SignupAccountService.Status status = signups.status(accountId);
    if (status == null) {
      throw accountNotFound();
    }
    JSONObject body = new JSONObject()
        .put(FIELD_ACCOUNT_ID, status.accountId())
        .put(FIELD_EMAIL, status.email())
        .put(FIELD_STATUS, status.status());
    if (status.error() != null) {
      body.put("error", status.error());
    }
    writeJson(response, HttpServletResponse.SC_OK, body);
  }

  /**
   * {@code POST /accounts/{id}/login-link}: a fresh one-time login link, replacing any earlier
   * one, and the ready mail with it when {@code sendEmail} is true.
   */
  private void handleLoginLink(String accountId, HttpServletRequest request,
      HttpServletResponse response) throws IOException, SignupException, JSONException {
    JSONObject body = readOptionalBody(request);
    SignupAccountService.LoginLink link = signups.issueLoginLink(accountId,
        body.optBoolean("sendEmail", false),
        StringUtils.trimToNull(SignupRequest.optionalText(body, SignupRequest.FIELD_LANGUAGE)));
    if (link.refusal() == SignupAccountService.LinkRefusal.NOT_FOUND) {
      throw accountNotFound();
    }
    if (link.refusal() == SignupAccountService.LinkRefusal.NOT_READY) {
      throw conflict(CODE_ENVIRONMENT_NOT_READY, null,
          "The account's environment is not ready yet");
    }
    if (link.refusal() == SignupAccountService.LinkRefusal.NOT_CONFIGURED) {
      throw new SignupException(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          CODE_LOGIN_LINK_UNAVAILABLE, null, "The server has no public app URL configured");
    }
    writeJson(response, HttpServletResponse.SC_OK, new JSONObject()
        .put("link", link.link())
        .put("expiresAt", link.expiresAt().toInstant().toString())
        .put("emailSent", link.emailSent()));
  }

  /** {@code POST /accounts/{id}/retry}: only a failed creation is run again. */
  private void handleRetry(String accountId, HttpServletResponse response)
      throws IOException, SignupException, JSONException {
    SignupAccountService.RetryOutcome outcome = signups.retry(accountId);
    if (outcome == SignupAccountService.RetryOutcome.NOT_FOUND) {
      throw accountNotFound();
    }
    if (outcome == SignupAccountService.RetryOutcome.NOT_FAILED) {
      throw conflict(CODE_RETRY_NOT_ALLOWED, null,
          "Only a failed environment creation can be retried");
    }
    writeJson(response, HttpServletResponse.SC_ACCEPTED, new JSONObject()
        .put(FIELD_ACCOUNT_ID, accountId)
        .put(FIELD_STATUS, "provisioning"));
  }

  /** A unit of account work that may refuse the request. */
  @FunctionalInterface
  interface AccountWork {
    /**
     * Does the work, writing its own response.
     *
     * @throws SignupException when the request is refused, answered with its status and code
     */
    void run() throws SignupException;
  }

  /**
   * Runs account reads in the SYSTEM admin context, the one {@code EtendoGoJwtServlet} uses for
   * {@code /register}: accounts belong to client 0 and the request carries no session.
   */
  void runAsSystem(AccountWork work) throws SignupException {
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      work.run();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static JSONObject readBody(HttpServletRequest request) throws IOException,
      SignupException {
    return parseBody(readText(request));
  }

  /** A body that may be left out: no body reads as an empty object. */
  private static JSONObject readOptionalBody(HttpServletRequest request) throws IOException,
      SignupException {
    String text = readText(request);
    return StringUtils.isBlank(text) ? new JSONObject() : parseBody(text);
  }

  private static JSONObject parseBody(String text) throws SignupException {
    try {
      return new JSONObject(text);
    } catch (JSONException e) {
      throw SignupRequest.badRequest(SignupRequest.CODE_INVALID_REQUEST, null,
          "The body is not a JSON object");
    }
  }

  private static String readText(HttpServletRequest request) throws IOException {
    StringBuilder text = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        text.append(line);
      }
    }
    return text.toString();
  }

  private static SignupException accountNotFound() {
    return new SignupException(HttpServletResponse.SC_NOT_FOUND, CODE_ACCOUNT_NOT_FOUND, null,
        "No account created through the signup API has this id");
  }

  private static SignupException conflict(String code, String field, String message) {
    return new SignupException(HttpServletResponse.SC_CONFLICT, code, field, message);
  }

  /**
   * {@code { "error": { "code", "field", "max", "message" } }}: {@code field} and {@code max} only
   * when they apply. Callers branch on {@code code}; {@code message} is English, for logs.
   */
  static void writeError(HttpServletResponse response, SignupException e) throws IOException {
    try {
      JSONObject error = new JSONObject().put("code", e.code()).put("message", e.getMessage());
      if (e.field() != null) {
        error.put("field", e.field());
      }
      if (e.max() != null) {
        error.put("max", e.max());
      }
      writeJson(response, e.status(), new JSONObject().put("error", error));
    } catch (JSONException jsonError) {
      log.error("Could not build the signup error response", jsonError);
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
