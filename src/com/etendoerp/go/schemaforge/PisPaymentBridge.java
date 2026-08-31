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

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.actionhandler.GenerateBankPayment;
import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUrlUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;

/**
 * Bridges a NEO-registered {@link FIN_Payment} to the PSD2 module's PIS (Payment Initiation
 * Service) flow, so paying a purchase invoice by real bank transfer from the Etendo Go "Add
 * payment" modal goes through the exact same Salt Edge logic as Classic's "Generate Bank Payment"
 * process ({@code com.etendoerp.psd2.bank.integration.actionhandler.GenerateBankPayment}).
 *
 * <p>Rather than re-implementing the ~300 lines of payload building / SEPA-FPS-DOMESTIC validation
 * / persistence, this reuses {@code GenerateBankPayment.processPayment(...)} directly — that method
 * is public and takes an explicit {@code returnToUrl}. No PSD2 logic is copied or changed here.
 *
 * <p><b>Where the bank sends the browser back (ETP-4895).</b> Salt Edge's {@code return_to} points
 * at {@link PisReturnCallbackServlet}, not at the SPA: that servlet resolves the payment's final
 * status server-side and creates it, exactly as Classic's own callback does, so a transfer is
 * registered even when the tab that started it is already closed. The SPA page the popup should
 * finally land on is persisted separately on the PSD2 row's {@code return_to_url} column, which
 * that servlet reads when bouncing the browser onward.
 *
 * <p>The {@code params} JSON built here uses the exact same keys that {@code GenerateBankPayment}
 * normalizes: {@code template}, {@code end_to_end_id}, {@code creditor_name}, {@code amount},
 * {@code currency_id}, {@code description}, {@code creditor_iban}. Template selection is
 * currency-driven — {@code SEPA} for EUR, {@code FPS} for GBP — any other currency must already
 * have been rejected by the caller's eligibility check (see
 * {@code PaymentRegistrationService#validatePisEligibility}) before this class is reached.
 */
final class PisPaymentBridge {

  // Mirrors the private constants of GenerateBankPayment (not public there, so redeclared here).
  private static final String KEY_TEMPLATE = "template";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_CURRENCY_ID = "currency_id";
  private static final String KEY_DESCRIPTION = "description";
  private static final String TEMPLATE_SEPA = "SEPA";
  private static final String TEMPLATE_FPS = "FPS";
  private static final String CURRENCY_GBP = "GBP";

  private static final Logger log = LogManager.getLogger(PisPaymentBridge.class);

  /**
   * App-shell SPA route the popup finally lands on. It is a tiny page that just closes the popup
   * and tells the "Add payment" modal the user is back, so they land on the invoice instead of the
   * Classic-styled shared bank-auth result page. Mirrors the AIS connect flow's
   * {@code /financial-account/bank-connection-callback}.
   *
   * <p>Salt Edge no longer redirects here directly: it returns to
   * {@link #PIS_RETURN_SERVLET_PATH} first, so the status is resolved and the payment created
   * server-side, and only then is the browser bounced here. This URL is persisted on the PSD2 row
   * ({@code return_to_url}) so the servlet knows where to send it.
   */
  private static final String PIS_CALLBACK_PATH = "/financial-account/pis-callback";

  /**
   * Backend route Salt Edge is told to return to after SCA — {@link PisReturnCallbackServlet}.
   * Registered as an exact servlet mapping so it is served there rather than by the {@code /sws/*}
   * SecureWebServices dispatcher, and reachable with no session (the bank redirects a bare
   * browser, carrying no bearer token).
   */
  private static final String PIS_RETURN_SERVLET_PATH = "/sws/pis-return";

  private static final String HEADER_FORWARDED_PROTO = "X-Forwarded-Proto";
  private static final String HEADER_FORWARDED_HOST = "X-Forwarded-Host";

  private PisPaymentBridge() {
  }

  /**
   * Builds the PIS payment request for {@code payment} and delegates to
   * {@link GenerateBankPayment#processPayment}, returning the Salt Edge payment id + SCA widget
   * URL.
   *
   * <p>The payment-derived fields (end-to-end id, creditor name, amount, currency, description)
   * are set here; the template and creditor account identifiers come from {@code pisInput} — the
   * user's choices in the SPA (mirroring the classic "Generate Bank Payment" dialog). When the
   * template is missing it defaults from the currency (EUR→SEPA, GBP→FPS) for backwards
   * compatibility. {@code GenerateBankPayment} validates which creditor fields are required per
   * template.
   *
   * @param payment  the draft {@link FIN_Payment} to submit for a real bank transfer
   * @param pisInput template + creditor fields, keyed by the orchestrator's parameter names
   *                 ({@code template}, {@code creditor_iban}, {@code creditor_bban},
   *                 {@code creditor_account_number}, {@code creditor_sort_code}); may be {@code null}
   * @param request  the current HTTP request (used by GenerateBankPayment to resolve the client's
   *                 real IP for PSD2 fraud-detection headers); may be {@code null}
   * @return the Salt Edge create-payment result (payment id + payment URL)
   * @throws JSONException if building the request payload fails
   */
  static BankIntegrationPISUtils.PISCreatePaymentResult initiatePisPayment(FIN_Payment payment,
      JSONObject pisInput, HttpServletRequest request) throws JSONException {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(OBContext.getOBContext().getCurrentClient());

    JSONObject params = pisInput != null ? pisInput : new JSONObject();
    if (!params.has(KEY_TEMPLATE) || StringUtils.isBlank(params.optString(KEY_TEMPLATE, null))) {
      params.put(KEY_TEMPLATE, templateForCurrency(payment.getCurrency().getISOCode()));
    }
    // The payment's own documentNo is only the default. A retry passes its own reference, because
    // end-to-end ids must be unique per debtor account and resending this one verbatim risks a
    // silent bank-side reject or a false "already processed" match.
    if (StringUtils.isBlank(params.optString(BankIntegrationConstants.END_TO_END_ID, null))) {
      params.put(BankIntegrationConstants.END_TO_END_ID, payment.getDocumentNo());
    }
    params.put(BankIntegrationConstants.CREDITOR_NAME, payment.getBusinessPartner().getName());
    params.put(KEY_AMOUNT, payment.getAmount().toString());
    params.put(KEY_CURRENCY_ID, payment.getCurrency().getId());
    params.put(KEY_DESCRIPTION, descriptionFor(payment));

    // Salt Edge returns to our own servlet, which resolves the status server-side and only then
    // bounces the browser to the SPA page below — so the payment is registered even if the tab
    // that started it is gone. The SPA URL is null when the request origin can't be resolved; the
    // servlet then serves its own "you can close this window" fallback.
    String appReturnUrl = resolveGoReturnUrl(request);

    // Reuse Classic's exact payload/validation/persistence via its now-public processPayment.
    BankIntegrationPISUtils.PISCreatePaymentResult result = new GenerateBankPayment()
        .processPayment(payment, params, apiKey, request, resolveBackendReturnUrl(request));
    persistAppReturnUrl(result.getPaymentId(), appReturnUrl);
    return result;
  }

  /**
   * Initiates a PIS transfer for an invoice <em>before</em> any {@link FIN_Payment} exists, so
   * Etendo Go can wait for Salt Edge to confirm a resolutive status before registering the payment
   * (see {@code PisDeferredPaymentService}).
   *
   * <p>Reuses the very same {@code GenerateBankPayment} core as the classic path through its
   * {@code PisRequestContext} overload — only the source of the payment-derived fields differs
   * (invoice + selected account here, the FIN_Payment there). Validation, payload shape and
   * persistence are therefore identical.
   *
   * @param endToEndId
   *     the bank reference for this attempt; the caller guarantees it is unique, since with no
   *     payment there is no {@code documentNo} to borrow and a reused reference risks a duplicate
   *     rejection at the bank
   */
  static BankIntegrationPISUtils.PISCreatePaymentResult initiateDeferredPisPayment(Invoice invoice,
      FIN_FinancialAccount account, BigDecimal amount, String endToEndId, JSONObject pisInput,
      HttpServletRequest request) throws JSONException {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(OBContext.getOBContext().getCurrentClient());

    JSONObject params = pisInput != null ? pisInput : new JSONObject();
    if (!params.has(KEY_TEMPLATE) || StringUtils.isBlank(params.optString(KEY_TEMPLATE, null))) {
      params.put(KEY_TEMPLATE, templateForCurrency(invoice.getCurrency().getISOCode()));
    }
    params.put(BankIntegrationConstants.END_TO_END_ID, endToEndId);
    params.put(BankIntegrationConstants.CREDITOR_NAME, invoice.getBusinessPartner().getName());
    params.put(KEY_AMOUNT, amount.toString());
    params.put(KEY_CURRENCY_ID, invoice.getCurrency().getId());
    params.put(KEY_DESCRIPTION, invoice.getDocumentNo());

    GenerateBankPayment.PisRequestContext context = GenerateBankPayment.PisRequestContext.of(
        account, invoice.getBusinessPartner(), amount, invoice.getCurrency(), endToEndId,
        invoice.getDocumentNo());

    String appReturnUrl = resolveGoReturnUrl(request);
    BankIntegrationPISUtils.PISCreatePaymentResult result = new GenerateBankPayment()
        .processPayment(context, params, apiKey, request, resolveBackendReturnUrl(request));
    persistAppReturnUrl(result.getPaymentId(), appReturnUrl);
    return result;
  }

  /**
   * Absolute URL of {@link PisReturnCallbackServlet}, the address Salt Edge sends the browser back
   * to after SCA.
   *
   * <p>Taken from the request that is initiating the payment, because that request already carries
   * the address the browser is actually reaching this server at: the deployed context path comes
   * from Tomcat itself, so it cannot be doubled, and the host is whatever the proxy is publishing.
   *
   * <p><b>Why not the configured base URL alone.</b> {@link BankIntegrationUrlUtils#buildBaseUrl()}
   * composes {@code context.url} with {@code context.name}. That holds for PSD2's own convention
   * ({@code context.url} = bare server, as its README documents), but Etendo's own
   * {@code Openbravo.properties.template} ships {@code context.url} WITH the context path — and on
   * a server configured that way the result repeats it, so Salt Edge was sent to
   * {@code https://host/etendo/etendo/sws/pis-return}. That path matches no servlet mapping and the
   * user landed on Etendo's generic error page after paying (ETP-4895). PSD2's helper is shared
   * with Classic and keeps its convention untouched; the collapse below is applied on this side.
   *
   * <p>The configured base URL is still the fallback, for a request that cannot say where it is
   * publicly reachable — behind a proxy that forwards no {@code X-Forwarded-*}, Tomcat sees its own
   * internal address, which is useless to a bank redirecting a browser.
   */
  private static String resolveBackendReturnUrl(HttpServletRequest request) {
    String fromRequest = publicBaseFromRequest(request);
    String base = fromRequest != null ? fromRequest
        : collapseRepeatedContext(BankIntegrationUrlUtils.buildBaseUrl());
    String returnUrl = StringUtils.removeEnd(base, "/") + PIS_RETURN_SERVLET_PATH;
    // One line per initiated transfer (a handful a day), and the only way to tell from a server's
    // logs which of the two branches produced the address the bank was given.
    log.info("PIS return URL: {} (from {}; X-Forwarded-Proto={}, X-Forwarded-Host={}, Host={})",
        returnUrl, fromRequest != null ? "request" : "context.url",
        header(request, HEADER_FORWARDED_PROTO), header(request, HEADER_FORWARDED_HOST),
        header(request, "Host"));
    return returnUrl;
  }

  /**
   * Where this server is publicly reachable, according to the request being served, or {@code null}
   * when that cannot be established.
   *
   * <p>{@code X-Forwarded-Proto} / {@code X-Forwarded-Host} win when the proxy sets them (each may
   * carry a comma-separated chain — the first entry is the original client-facing hop). Otherwise
   * the request's own scheme and host are used, which is correct for a directly exposed Tomcat and
   * wrong behind a silent proxy — hence the reachability check: an address a bank cannot redirect a
   * browser to is worse than falling back to the configured one.
   */
  private static String publicBaseFromRequest(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String proto = firstHop(header(request, HEADER_FORWARDED_PROTO));
    String host = firstHop(header(request, HEADER_FORWARDED_HOST));
    if (proto == null) {
      proto = request.getScheme();
    }
    if (host == null) {
      host = request.getServerName() + defaultPortSuffix(request);
    }
    if (!isPubliclyAddressable(host)) {
      return null;
    }
    return proto + "://" + host + StringUtils.trimToEmpty(request.getContextPath());
  }

  /** The first entry of a possibly comma-separated proxy header chain, or {@code null}. */
  private static String firstHop(String headerValue) {
    return StringUtils.trimToNull(StringUtils.substringBefore(StringUtils.trimToEmpty(headerValue), ","));
  }

  private static String header(HttpServletRequest request, String name) {
    return request != null ? request.getHeader(name) : null;
  }

  /** {@code :port} unless it is the default for the scheme, which browsers omit. */
  private static String defaultPortSuffix(HttpServletRequest request) {
    int port = request.getServerPort();
    boolean isDefault = ("http".equals(request.getScheme()) && port == 80)
        || ("https".equals(request.getScheme()) && port == 443);
    return isDefault || port <= 0 ? "" : ":" + port;
  }

  /**
   * Whether a bank could redirect a browser to this host. Loopback and single-label names (a
   * container or service name) are only reachable from inside the deployment.
   */
  private static boolean isPubliclyAddressable(String host) {
    String name = StringUtils.substringBefore(StringUtils.trimToEmpty(host), ":");
    if (StringUtils.isBlank(name) || StringUtils.equalsAnyIgnoreCase(name, "localhost", "127.0.0.1",
        "::1", "0.0.0.0")) {
      return false;
    }
    return StringUtils.contains(name, ".");
  }

  /**
   * Undoes the context path {@link BankIntegrationUrlUtils#buildBaseUrl()} repeats when
   * {@code context.url} already ends with {@code context.name}.
   *
   * <p>Deliberately narrow: it collapses only a base ending in exactly
   * {@code /<context.name>/<context.name>}, the one shape that composition can produce. Anything
   * else is passed through untouched, so a deployment that genuinely nests a path is not mangled.
   */
  private static String collapseRepeatedContext(String baseUrl) {
    String name = StringUtils.trimToNull(
        OBPropertiesProvider.getInstance().getOpenbravoProperties().getProperty("context.name"));
    if (name == null || baseUrl == null) {
      return baseUrl;
    }
    String bare = StringUtils.stripStart(name, "/");
    String doubled = "/" + bare + "/" + bare;
    return StringUtils.endsWith(baseUrl, doubled)
        ? StringUtils.removeEnd(baseUrl, "/" + bare)
        : baseUrl;
  }

  /**
   * Stores the SPA page the popup should end up on once {@link PisReturnCallbackServlet} is done.
   *
   * <p>Written by fetching the row PSD2 just created rather than by widening
   * {@code processPayment}'s signature: {@code return_to_url} is an existing, unused column, and
   * this is the same fetch-back-then-set-one-field pattern {@code PisDeferredPaymentService
   * #retryReusingPayment} already uses for {@code endToEnd}. Keeps the change entirely inside
   * Etendo Go — PSD2 is untouched.
   *
   * <p>Failing here is not fatal: without it the servlet still resolves the payment and just serves
   * its own fallback page instead of returning the user to the invoice.
   */
  private static void persistAppReturnUrl(String saltedgePaymentId, String appReturnUrl) {
    if (StringUtils.isBlank(appReturnUrl) || StringUtils.isBlank(saltedgePaymentId)) {
      return;
    }
    try {
      PisPayment pisPayment = PISPaymentDao.findBySaltedgePaymentId(saltedgePaymentId);
      if (pisPayment == null) {
        log.warn("Could not persist app return URL: no PisPayment for Salt Edge id {}",
            saltedgePaymentId);
        return;
      }
      pisPayment.setReturnToUrl(appReturnUrl);
      OBDal.getInstance().save(pisPayment);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.warn("Could not persist app return URL for Salt Edge id {}: {}", saltedgePaymentId,
          e.getMessage());
    }
  }

  private static String descriptionFor(FIN_Payment payment) {
    return payment.getDescription() != null ? payment.getDescription() : payment.getDocumentNo();
  }

  /**
   * Builds the app-shell return URL from the request {@code Origin} header (falling back to
   * {@code Referer}), mirroring {@code FinancialAccountBankConnectionHandler#resolveAppShellOrigin}. Returns
   * {@code null} when the origin can't be resolved, so the caller keeps the default callback.
   */
  private static String resolveGoReturnUrl(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String origin = StringUtils.trimToNull(request.getHeader("Origin"));
    if (origin == null) {
      origin = FinancialAccountBankConnectionSupport.originFromReferer(request.getHeader("Referer"));
    }
    if (origin == null) {
      return null;
    }
    return StringUtils.removeEnd(origin, "/") + PIS_CALLBACK_PATH;
  }

  /** SEPA for EUR, FPS for GBP. Fallback only — the SPA normally sends the template explicitly. */
  private static String templateForCurrency(String isoCode) {
    return CURRENCY_GBP.equalsIgnoreCase(isoCode) ? TEMPLATE_FPS : TEMPLATE_SEPA;
  }
}
