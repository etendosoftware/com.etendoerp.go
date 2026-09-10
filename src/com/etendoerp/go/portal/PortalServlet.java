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

package com.etendoerp.go.portal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The Business Partner self-service portal's read-only API, at {@code /sws/portal/*} (ETP-5267).
 *
 * <pre>
 * GET /sws/portal/me[/{token}]                 the Business Partner and tenant the token speaks for
 * GET /sws/portal/invoices                     completed sales invoices + outstanding balance
 * GET /sws/portal/invoices/{invoiceId}/pdf     the invoice's main attachment, streamed
 * </pre>
 *
 * <p><b>Deliberately its own servlet, isolated from NEO Headless.</b> It is never reachable through
 * {@code NeoServlet}, {@code NeoCrudHandler} or {@code DataSourceServlet} — the generic CRUD engine
 * every tenant and window shares — and no portal logic lives in any generic NEO service. The reason
 * is the threat model: this is the module's only endpoint family whose caller is an outside party
 * holding nothing but a link. Routing it through an engine designed to expose arbitrary entities to
 * an authenticated employee would make every future NEO feature a potential portal exposure. The one
 * thing it reuses is the invoice PDF pipeline (see {@link #handleInvoicePdf}), which is a read of an
 * already scope-checked record.
 *
 * <p><b>Reachable with no Etendo session, by design.</b> {@code /sws/portal/*} is a longer
 * path-prefix mapping than {@code /sws/*}, so the container serves it here and the request never
 * reaches the SecureWebServices dispatcher; none of the four global {@code /*} filters rejects a
 * session-less request. Same mechanism {@code PisReturnCallbackServlet} documents for
 * {@code /sws/pis-return} and {@code NeoServlet} uses for its unauthenticated document-download and
 * image-upload-ticket paths. <b>The token is the credential.</b>
 *
 * <h2>Security invariants (plan §5), and where each one lives</h2>
 * <ol>
 *   <li><b>Scope comes only from the validated row.</b> Structural: {@link PortalSession} has no
 *       public constructor, so no endpoint here can obtain a client or Business Partner id from a
 *       request parameter. There is nothing to review per-endpoint.</li>
 *   <li><b>Unknown and revoked are indistinguishable.</b> {@link PortalAccessService#validate}
 *       answers empty for both, and {@link #rejectInvalidLink} is the single response for every
 *       such case.</li>
 *   <li><b>Every invoice read filters tenant + Business Partner + {@code docstatus = 'CO'}.</b>
 *       Centralised in {@link PortalInvoiceQuery}; this servlet composes no invoice query of its
 *       own.</li>
 *   <li><b>The token is never logged.</b> No log statement in this package takes it, not even at
 *       debug. Requests are identified by the access row id, which is safe to log.</li>
 *   <li><b>Every response is {@code no-store}.</b> Set in {@link #service} before any handler runs,
 *       so it cannot be forgotten on a new endpoint. Without it a shared cache keyed on path alone
 *       could serve one Business Partner's data to another.</li>
 *   <li><b>{@code /me} is rate limited</b> ({@link PortalRateLimiter}) as defence in depth.</li>
 *   <li><b>Out of scope answers 404, never 403</b> — a 403 would confirm the record exists.</li>
 * </ol>
 *
 * <p>Runs in admin mode with no session to inherit one from, exactly as
 * {@code NeoServlet#handleDocumentDownload} does for its own unauthenticated path. The elevation
 * grants no scope the token did not already carry: every query states its own tenant and Business
 * Partner filter and switches DAL's context-based filtering off, because that context would be the
 * SYSTEM bootstrap and would match nothing.
 */
public class PortalServlet extends HttpBaseServlet {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LogManager.getLogger(PortalServlet.class);

  private static final String PATH_ME = "/me";
  private static final String PATH_INVOICES = "/invoices";
  private static final String PDF_SUFFIX = "/pdf";
  /** {@code AD_Table.name} holding sales-invoice attachments, as {@code NeoDocumentDownloadService} maps it. */
  private static final String TABLE_C_INVOICE = "C_Invoice";
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CONTENT_TYPE_JSON = "application/json";
  /** {@code HttpServletResponse} has no constant for 429. */
  private static final int HTTP_TOO_MANY_REQUESTS = 429;

  /**
   * The one message every rejected token gets, whatever the reason. Kept deliberately free of
   * detail: the frontend renders its own localized "this link is no longer valid" page, and the
   * response must not distinguish unknown from revoked from malformed.
   */
  private static final String INVALID_LINK = "This link is no longer valid";
  /** The answer to every out-of-scope or unmapped request. Never {@code 403} — see invariant 7. */
  private static final String NOT_FOUND = "Not found";

  private final transient PortalAccessService accessService = new PortalAccessService();
  private final transient PortalRateLimiter meRateLimiter = new PortalRateLimiter();

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    // Set before dispatch so no endpoint can ship without it, and before anything can commit the
    // response. Invariant 5.
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
    response.setHeader("Pragma", "no-cache");
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "The portal API is read-only");
      return;
    }
    doGet(request, response);
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = StringUtils.defaultString(request.getPathInfo());
    try {
      OBContext.setAdminMode(true);
      if (path.equals(PATH_ME) || path.startsWith(PATH_ME + "/")) {
        handleMe(request, response);
      } else if (path.equals(PATH_INVOICES)) {
        handleInvoices(request, response);
      } else if (path.startsWith(PATH_INVOICES + "/") && path.endsWith(PDF_SUFFIX)) {
        handleInvoicePdf(request, response, invoiceIdFromPdfPath(path));
      } else {
        sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      }
    } catch (Exception e) {
      // Nothing identifying the caller is logged: only /me can carry the token, and it carries it
      // in the path, so neither the path nor the token appears in this message.
      log.error("Portal request failed", e);
      if (!response.isCommitted()) {
        response.reset();
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "The portal is temporarily unavailable");
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Validates the token and answers who it speaks for. The portal's entry point, and the only
   * rate-limited endpoint.
   */
  private void handleMe(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    if (!meRateLimiter.tryAcquire(request.getRemoteAddr())) {
      sendError(response, HTTP_TOO_MANY_REQUESTS, "Too many requests");
      return;
    }
    Optional<PortalSession> session = accessService.validate(resolveToken(request));
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    PortalSession validated = session.get();
    // Flat `businessPartnerName` / `tenantName`, per the payload contract the frontend is built
    // against. Both are always present as strings, empty when the record carries no name: the
    // header degrades, it never errors.
    JSONObject body = new JSONObject();
    body.put("businessPartnerName", StringUtils.defaultString(validated.getBusinessPartnerName()));
    body.put("tenantName", StringUtils.defaultString(validated.getTenantName()));
    sendJson(response, HttpServletResponse.SC_OK, body);
  }

  /** Lists the Business Partner's completed sales invoices and their outstanding balance. */
  private void handleInvoices(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    Optional<PortalSession> session = accessService.validate(resolveToken(request));
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    List<Invoice> invoices = PortalInvoiceQuery.list(session.get());
    JSONArray rows = new JSONArray();
    // Accumulated per currency, never summed across them: a tenant that invoices the same Business
    // Partner in EUR and USD has two balances, and adding them would produce a number that means
    // nothing. The contract's top-level `currency` + `outstandingAmount` are the single-currency
    // case — which is the real one for essentially every tenant — and `balances` below carries the
    // full breakdown for the mixed case. Insertion-ordered, so the first currency encountered
    // (newest invoice first) is the one promoted to the top level.
    Map<String, BigDecimal> outstandingByCurrency = new LinkedHashMap<>();
    for (Invoice invoice : invoices) {
      rows.put(toInvoiceJson(invoice));
      outstandingByCurrency.merge(currencyOf(invoice), amount(invoice.getOutstandingAmount()),
          BigDecimal::add);
    }
    JSONObject body = new JSONObject();
    body.put("invoices", rows);
    // No `status` and no invoice count, both by contract: the browser derives status by comparing
    // outstandingAmount against grandTotalAmount, and the count from invoices.length. That makes
    // PortalInvoiceQuery's docstatus = 'CO' filter load-bearing — the browser makes no scoping or
    // eligibility decision at all.
    body.put("currency", primaryCurrency(outstandingByCurrency));
    body.put("outstandingAmount", primaryOutstanding(outstandingByCurrency));
    // Additive, and deliberately outside the agreed contract: extra keys are inert in the frontend,
    // and dropping the breakdown would silently misreport a mixed-currency Business Partner.
    body.put("balances", toBalancesJson(outstandingByCurrency));
    sendJson(response, HttpServletResponse.SC_OK, body);
  }

  /**
   * The currency the top-level balance is expressed in: the only one when the Business Partner is
   * invoiced in a single currency, and the newest invoice's otherwise.
   */
  private static String primaryCurrency(Map<String, BigDecimal> outstandingByCurrency) {
    return outstandingByCurrency.isEmpty() ? ""
        : outstandingByCurrency.keySet().iterator().next();
  }

  /**
   * The top-level outstanding balance, in {@link #primaryCurrency}. Only that currency's total —
   * never a cross-currency sum.
   */
  private static BigDecimal primaryOutstanding(Map<String, BigDecimal> outstandingByCurrency) {
    return outstandingByCurrency.isEmpty() ? BigDecimal.ZERO
        : outstandingByCurrency.values().iterator().next();
  }

  /**
   * Streams one invoice's PDF.
   *
   * <p><b>Reuses the existing invoice PDF pipeline rather than rendering anything.</b> The file
   * served is the attachment currently marked "main" for the {@code C_Invoice} record — the same
   * one the backoffice sidebar shows and the same one a signed email download link serves
   * (ETP-4315). {@link NeoAttachmentsHelper#handleGetMain} resolves it and
   * {@link NeoAttachmentsHelper#handleDownload} streams it; both are the public entry points that
   * {@code NeoDocumentDownloadService} sits beside, so there is one implementation of "this
   * document's file" for all three callers.
   *
   * <p>Scope is checked twice on purpose: {@link PortalInvoiceQuery#findInScope} first, so an id
   * outside the token's scope never reaches the attachment layer at all, and then the resolved
   * attachment's own client, mirroring {@code NeoDocumentDownloadService#resolveMainAttachment}.
   * Both misses answer {@code 404}.
   */
  private void handleInvoicePdf(HttpServletRequest request, HttpServletResponse response,
      String invoiceId) throws IOException, JSONException {
    Optional<PortalSession> session = accessService.validate(resolveToken(request));
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    PortalSession validated = session.get();
    Invoice invoice = PortalInvoiceQuery.findInScope(validated, invoiceId);
    if (invoice == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    String attachmentId = resolveMainAttachmentId(invoice.getId());
    if (attachmentId == null || !belongsToClient(attachmentId, validated.getClientId())) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    NeoAttachmentsHelper.handleDownload(attachmentId, response);
  }

  private String resolveMainAttachmentId(String invoiceId) {
    NeoResponse main = NeoAttachmentsHelper.handleGetMain(TABLE_C_INVOICE, invoiceId);
    if (main == null || main.getHttpStatus() != HttpServletResponse.SC_OK
        || main.getBody() == null) {
      return null;
    }
    return StringUtils.trimToNull(main.getBody().optString("id"));
  }

  private boolean belongsToClient(String attachmentId, String clientId) {
    Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
    return attachment != null && attachment.getClient() != null
        && attachment.getClient().getId().equals(clientId);
  }

  /**
   * Reads the token from the {@code Authorization} header, falling back to the trailing path
   * segment of {@code /me/{token}}.
   *
   * <p>Both are supported because the two carry different exposure: the header keeps the token out
   * of access logs and {@code Referer}, which is why every call after the first uses it, while the
   * path form exists for the very first load, where the SPA has the token from its own route and no
   * session to put it in (plan §4.4).
   */
  private String resolveToken(HttpServletRequest request) {
    String header = StringUtils.trimToNull(request.getHeader(HEADER_AUTHORIZATION));
    if (header != null && StringUtils.startsWithIgnoreCase(header, BEARER_PREFIX)) {
      return StringUtils.trimToNull(header.substring(BEARER_PREFIX.length()));
    }
    String path = StringUtils.defaultString(request.getPathInfo());
    if (path.startsWith(PATH_ME + "/")) {
      return StringUtils.trimToNull(path.substring(PATH_ME.length() + 1));
    }
    return null;
  }

  private static String invoiceIdFromPdfPath(String path) {
    String withoutPrefix = path.substring((PATH_INVOICES + "/").length());
    return StringUtils.trimToNull(
        withoutPrefix.substring(0, withoutPrefix.length() - PDF_SUFFIX.length()));
  }

  /**
   * Serializes one invoice row to the agreed payload contract.
   *
   * <p><b>Amounts are JSON numbers, and they get there as {@link BigDecimal}.</b> The browser
   * derives each invoice's status by comparing {@code outstandingAmount} against
   * {@code grandTotalAmount}, so a quoted string would break that comparison while still rendering
   * a plausible amount — a silent failure. Handing jettison the {@code BigDecimal} itself (verified
   * against jettison 1.3: {@code 1210.50} serializes as the bare number {@code 1210.5}) keeps the
   * exact decimal digits, unlike {@code doubleValue()}, which is the other way this module has
   * emitted amounts and which introduces a binary-floating-point round trip.
   *
   * <p>Amounts are never <em>formatted</em> here. Choosing separators is the browser's job through
   * {@code formatCurrency}, which reads the instance-wide configuration; the backend must not pick
   * a locale on the customer's behalf.
   */
  private static JSONObject toInvoiceJson(Invoice invoice) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", invoice.getId());
    json.put("documentNo", StringUtils.defaultString(invoice.getDocumentNo()));
    json.put("invoiceDate", formatDate(invoice.getInvoiceDate()));
    json.put("dueDate", formatDate(invoice.getETGODueDate()));
    json.put("currency", currencyOf(invoice));
    json.put("grandTotalAmount", amount(invoice.getGrandTotalAmount()));
    json.put("outstandingAmount", amount(invoice.getOutstandingAmount()));
    return json;
  }

  private static JSONArray toBalancesJson(Map<String, BigDecimal> outstandingByCurrency)
      throws JSONException {
    JSONArray balances = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : outstandingByCurrency.entrySet()) {
      JSONObject balance = new JSONObject();
      balance.put("currency", entry.getKey());
      balance.put("outstandingAmount", entry.getValue());
      balances.put(balance);
    }
    return balances;
  }

  private static String currencyOf(Invoice invoice) {
    return invoice.getCurrency() == null ? ""
        : StringUtils.defaultString(invoice.getCurrency().getISOCode());
  }

  private static BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Formats a business date as {@code yyyy-MM-dd}.
   *
   * <p>Date-only on purpose: an invoice date is a calendar day, and sending it as an instant is
   * what makes it render one day early in a negative-UTC-offset timezone. The frontend parses this
   * with {@code parseCalendarDate}, which relies on exactly this prefix.
   */
  private static String formatDate(Date value) {
    return value == null ? "" : new SimpleDateFormat(DATE_FORMAT).format(value);
  }

  /** The single response for every rejected token: unknown, revoked, malformed or absent. */
  private void rejectInvalidLink(HttpServletResponse response) throws IOException {
    sendError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_LINK);
  }

  private static void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    JSONObject body = new JSONObject();
    try {
      body.put("error", message);
    } catch (JSONException e) {
      // Unreachable: the value is a non-null String literal. Rethrown rather than swallowed so a
      // future change that makes it reachable fails loudly instead of sending an empty body.
      throw new IllegalStateException("Could not build the portal error body", e);
    }
    sendJson(response, status, body);
  }

  private static void sendJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body.toString());
  }
}
