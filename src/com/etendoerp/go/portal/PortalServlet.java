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
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.schemaforge.NeoAttachmentsDownloader;
import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The customer portal's API, at {@code /sws/portal/*} (ETP-5267, link model ETP-6209).
 *
 * <pre>
 * GET  /sws/portal/me[/{token}]                 the link's state, and who it speaks for when valid
 * POST /sws/portal/renew                        email a new link for an expired one
 * GET  /sws/portal/invoices                     completed sales invoices + outstanding balance
 * GET  /sws/portal/invoices/{invoiceId}/pdf     the invoice's main attachment, streamed
 * GET  /sws/portal/logo                         the issuing company's logo
 * GET  /sws/portal/home                         balances, per-kind tiles, recent documents
 * GET  /sws/portal/documents?type=…             one document kind, filtered and paged
 * GET  /sws/portal/documents/{kind}/{id}[/pdf]  one document's detail, or its printable
 * </pre>
 *
 * <p>{@code /invoices} and {@code /invoices/{id}/pdf} are the MVP's and are kept for existing
 * callers; the Part B portal page reads {@code /home} and {@code /documents} only.
 *
 * <p><b>One link per document email, valid for a fixed time</b> ({@link PortalLinkService}). On
 * {@code /me} a link is valid, expired, unavailable (customer blocked or inactive, or the issuing
 * company's plan expired) or invalid. An expired link is renewed by {@code POST /renew}, which the
 * page calls on its own; it is a POST, not a side effect of the GET, so link scanners that
 * pre-fetch GETs never trigger an email.
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
 *   <li><b>Unknown, pending and deactivated are indistinguishable.</b>
 *       {@link PortalLinkService#validate} answers {@code INVALID} for all of them, and
 *       {@link #rejectInvalidLink} is the single response — byte-identical to the MVP's. Only a
 *       link that was really emailed can answer {@code expired} or {@code unavailable}.</li>
 *   <li><b>Every invoice read filters tenant + Business Partner + {@code docstatus = 'CO'}.</b>
 *       Centralised in {@link PortalInvoiceQuery}; this servlet composes no invoice query of its
 *       own.</li>
 *   <li><b>The token is never logged.</b> No log statement in this package takes it, not even at
 *       debug. Requests are identified by the link id, which is safe to log.</li>
 *   <li><b>Every response is {@code no-store}.</b> Set in {@link #service} before any handler runs,
 *       so it cannot be forgotten on a new endpoint. Without it a shared cache keyed on path alone
 *       could serve one Business Partner's data to another.</li>
 *   <li><b>{@code /me} and {@code /renew} are rate limited</b> ({@link PortalRateLimiter}) as
 *       defence in depth.</li>
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
  private static final String PATH_RENEW = "/renew";
  private static final String PATH_INVOICES = "/invoices";
  /** ETP-6209 Part B: the home aggregates and the per-kind documents. */
  private static final String PATH_HOME = "/home";
  private static final String PATH_DOCUMENTS = "/documents";
  private static final String PDF_SUFFIX = "/pdf";

  /** Tenant logo, so the page can show whose portal this is. Takes no parameters. */
  private static final String PATH_LOGO = "/logo";

  /** Query parameters for paging {@code GET /invoices}. */
  private static final String PARAM_LIMIT = "limit";
  private static final String PARAM_OFFSET = "offset";

  /** Page size when the caller asks for none. */
  private static final int DEFAULT_LIMIT = 50;

  /**
   * Hard ceiling on page size.
   *
   * <p>It exists so the page size is <b>ours</b>, not the caller's: without it
   * {@code ?limit=1000000} makes an unauthenticated request serialize a Business Partner's entire
   * history, which is a denial-of-service knob handed to whoever holds a link. A caller asking for
   * more is clamped rather than rejected — the request is still answerable, just not on the
   * caller's terms.
   */
  private static final int MAX_LIMIT = 200;
  /** JSON field names this servlet writes into more than one response shape. */
  private static final String FIELD_CURRENCY = "currency";
  private static final String FIELD_OUTSTANDING_AMOUNT = "outstandingAmount";

  /** {@code AD_Table.name} holding sales-invoice attachments, as {@code NeoDocumentDownloadService} maps it. */
  private static final String TABLE_C_INVOICE = "C_Invoice";
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  /** The portal token's own header, independent of the app's session credential scheme. */
  static final String HEADER_PORTAL_TOKEN = "X-Portal-Token";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CONTENT_TYPE_JSON = "application/json";
  /** {@code HttpServletResponse} has no constant for 429. */
  private static final int HTTP_TOO_MANY_REQUESTS = 429;

  /**
   * The one message every rejected token gets, whatever the reason. Kept deliberately free of
   * detail: the frontend renders its own localized "this link is no longer valid" page, and the
   * response must not distinguish unknown from pending from deactivated from malformed.
   */
  private static final String INVALID_LINK = "This link is no longer valid";
  /** The answer to every out-of-scope or unmapped request. Never {@code 403} — see invariant 7. */
  private static final String NOT_FOUND = "Not found";

  /** JSON field and values of the link state {@code /me} and {@code /renew} answer with. */
  private static final String FIELD_STATE = "state";
  private static final String STATE_VALID = "valid";
  private static final String STATE_EXPIRED = "expired";
  private static final String STATE_UNAVAILABLE = "unavailable";
  private static final String STATE_RENEWED = "renewed";
  private static final String FIELD_RENEWAL = "renewal";
  private static final String RENEWAL_SENT = "sent";
  private static final String RENEWAL_RECENTLY_SENT = "recentlySent";
  private static final String FIELD_TENANT_NAME = "tenantName";
  private static final String FIELD_HAS_LOGO = "hasLogo";

  private final transient PortalLinkService linkService = new PortalLinkService();
  private final transient PortalRateLimiter meRateLimiter = new PortalRateLimiter();
  private final transient PortalRateLimiter renewRateLimiter = new PortalRateLimiter();

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    // Set before dispatch so no endpoint can ship without it, and before anything can commit the
    // response. Invariant 5.
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
    response.setHeader("Pragma", "no-cache");
    String path = StringUtils.defaultString(request.getPathInfo());
    // The one write is the renewal; every other endpoint is read-only.
    if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals(PATH_RENEW)) {
      doPost(request, response);
      return;
    }
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "The portal API is read-only");
      return;
    }
    doGet(request, response);
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      OBContext.setAdminMode(true);
      handleRenew(request, response);
    } catch (Exception e) {
      log.error("Portal renewal request failed", e);
      if (!response.isCommitted()) {
        response.reset();
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "The portal is temporarily unavailable");
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = StringUtils.defaultString(request.getPathInfo());
    try {
      OBContext.setAdminMode(true);
      if (path.equals(PATH_ME) || path.startsWith(PATH_ME + "/")) {
        handleMe(request, response);
      } else if (path.equals(PATH_HOME)) {
        handleHome(request, response);
      } else if (path.equals(PATH_DOCUMENTS)) {
        handleDocuments(request, response);
      } else if (path.startsWith(PATH_DOCUMENTS + "/")) {
        handleDocument(request, response, path.substring(PATH_DOCUMENTS.length() + 1));
      } else if (path.equals(PATH_INVOICES)) {
        handleInvoices(request, response);
      } else if (path.startsWith(PATH_INVOICES + "/") && path.endsWith(PDF_SUFFIX)) {
        handleInvoicePdf(request, response, invoiceIdFromPdfPath(path));
      } else if (path.equals(PATH_LOGO)) {
        handleLogo(request, response);
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
   * Validates the token and answers its state — and, when valid, who it speaks for. The portal's
   * entry point, and rate limited.
   *
   * <pre>
   * 401 {"error": "This link is no longer valid"}          unknown, pending, deactivated (as MVP)
   * 200 {"state": "valid", businessPartnerName, tenantName, hasLogo, entryDocument: {kind, id}}
   * 200 {"state": "expired", tenantName, hasLogo}          the page then calls POST /renew
   * 200 {"state": "unavailable", tenantName, hasLogo}      blocked, inactive or plan expired
   * </pre>
   *
   * <p>An expired or unavailable answer carries no Business Partner name and no document: the
   * holder of that link is no longer entitled to them. Which of the three closures applies is never
   * said.
   */
  private void handleMe(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    if (!meRateLimiter.tryAcquire(request.getRemoteAddr())) {
      sendError(response, HTTP_TOO_MANY_REQUESTS, "Too many requests");
      return;
    }
    PortalLinkService.Validation validation = linkService.validate(resolveToken(request));
    Optional<PortalLink> resolved = validation.getLink();
    if (validation.getState() == PortalLinkService.State.INVALID || !resolved.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    PortalLink link = resolved.get();
    JSONObject body = new JSONObject();
    body.put(FIELD_TENANT_NAME, StringUtils.defaultString(link.getClientName()));
    // Whether there is a logo to fetch, so the page never requests one that does not exist and
    // never has to hide a broken image. The bytes come from GET /logo, not inlined here: a base64
    // logo would bloat every /me response.
    body.put(FIELD_HAS_LOGO, resolveTenantLogo(link.getClientId()) != null);
    if (validation.getState() == PortalLinkService.State.EXPIRED) {
      body.put(FIELD_STATE, STATE_EXPIRED);
    } else if (validation.getState() == PortalLinkService.State.UNAVAILABLE) {
      body.put(FIELD_STATE, STATE_UNAVAILABLE);
    } else {
      body.put(FIELD_STATE, STATE_VALID);
      body.put("businessPartnerName", StringUtils.defaultString(link.getBusinessPartnerName()));
      JSONObject entryDocument = new JSONObject();
      entryDocument.put("kind", link.getDocumentKind().name());
      entryDocument.put("id", link.getRecordId());
      body.put("entryDocument", entryDocument);
      PortalUsageEvents.opened(link);
    }
    sendJson(response, HttpServletResponse.SC_OK, body);
  }

  /**
   * Emails a new link for an expired one, to the expired link's own recipients.
   *
   * <pre>
   * 401 {"error": "This link is no longer valid"}          unknown, pending, deactivated
   * 200 {"state": "renewed", "renewal": "sent"}            a new link went out
   * 200 {"state": "renewed", "renewal": "recentlySent"}    one went out recently; nothing sent now
   * 200 {"state": "unavailable"}                           blocked, inactive or plan expired
   * 200 {"state": "valid"}                                 the link has not expired
   * 503 {"error": "..."}                                   the email could not be sent; retry
   * </pre>
   */
  private void handleRenew(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    if (!renewRateLimiter.tryAcquire(request.getRemoteAddr())) {
      sendError(response, HTTP_TOO_MANY_REQUESTS, "Too many requests");
      return;
    }
    PortalLinkService.RenewalOutcome outcome = linkService.renew(resolveToken(request));
    if (outcome == PortalLinkService.RenewalOutcome.INVALID) {
      rejectInvalidLink(response);
      return;
    }
    if (outcome == PortalLinkService.RenewalOutcome.FAILED) {
      sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "The portal is temporarily unavailable");
      return;
    }
    JSONObject body = new JSONObject();
    if (outcome == PortalLinkService.RenewalOutcome.SENT) {
      body.put(FIELD_STATE, STATE_RENEWED);
      body.put(FIELD_RENEWAL, RENEWAL_SENT);
    } else if (outcome == PortalLinkService.RenewalOutcome.RECENTLY_SENT) {
      body.put(FIELD_STATE, STATE_RENEWED);
      body.put(FIELD_RENEWAL, RENEWAL_RECENTLY_SENT);
    } else if (outcome == PortalLinkService.RenewalOutcome.UNAVAILABLE) {
      body.put(FIELD_STATE, STATE_UNAVAILABLE);
    } else {
      body.put(FIELD_STATE, STATE_VALID);
    }
    sendJson(response, HttpServletResponse.SC_OK, body);
  }

  /**
   * Streams the tenant's own logo, so the customer can see whose portal this is.
   *
   * <p>The image served is the tenant's printed-document logo, resolved by
   * {@link CompanyLogoResolver} exactly as the GO printables resolve it (ETP-5541): the logo
   * uploaded on the Organization screen ({@code AD_OrgInfo}), falling back to
   * {@code AD_ClientInfo}. That is deliberate reuse: a customer looking
   * at this page has an invoice from the same company in hand, and the logo on both should match. It
   * also means a tenant configures branding once, in the place it already configures it.
   *
   * <p><b>Scoped by the token like everything else.</b> The client comes from the validated row, so
   * a token cannot fetch another tenant's logo, and no image id is accepted from the caller — this
   * endpoint takes no parameters at all.
   *
   * <p>A tenant with no logo answers {@code 404}, which {@code /me}'s {@code hasLogo} lets the page
   * avoid asking for in the first place.
   */
  private void handleLogo(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    // Any sent link, expired or closed included: the "this link expired" page shows whose portal
    // it is too, and a company's logo is no secret.
    Optional<PortalLink> link = linkService.findSentLink(resolveToken(request));
    if (!link.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    Image logo = resolveTenantLogo(link.get().getClientId());
    byte[] bytes = logo == null ? null : logo.getBindaryData();
    if (bytes == null || bytes.length == 0) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    // Sniffed from the bytes when the row carries no mime type, rather than trusting a stored
    // string: the browser renders what the header claims, so the header should describe the bytes.
    String mimeType = StringUtils.defaultIfBlank(logo.getMimetype(),
        NeoImageHelper.sniffMimeType(bytes));
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(StringUtils.defaultIfBlank(mimeType, NeoImageHelper.MIME_PNG));
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
  }

  /**
   * Resolves the tenant's document logo, or {@code null} when it has none.
   *
   * <p>Delegates to {@link CompanyLogoResolver} so the portal and the printed documents can never
   * disagree on the logo. A portal session carries no organization, so resolution starts at the
   * client's first organization logo. The resolver runs its own admin-mode reads: a portal request
   * has no Etendo session, so DAL's own client filtering would match nothing.
   */
  private static Image resolveTenantLogo(String clientId) {
    return CompanyLogoResolver.resolve(clientId, null);
  }

  /** Lists the Business Partner's completed sales invoices and their outstanding balance. */
  private void handleInvoices(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    Optional<PortalSession> session = linkService.validate(resolveToken(request)).getSession();
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    PortalSession validated = session.get();
    int limit = clampLimit(request.getParameter(PARAM_LIMIT));
    int offset = parseOffset(request.getParameter(PARAM_OFFSET));

    // The whole set is read for the aggregates below, then only one page is serialized. The totals
    // MUST span every invoice, not the page: a balance that shrank as the customer clicked "next"
    // would be a wrong number presented confidently, which is worse than showing none.
    List<Invoice> all = PortalInvoiceQuery.listAll(validated);
    // Accumulated per currency, never summed across them: a tenant that invoices the same Business
    // Partner in EUR and USD has two balances, and adding them would produce a number that means
    // nothing. The contract's top-level `currency` + `outstandingAmount` are the single-currency
    // case — which is the real one for essentially every tenant — and `balances` below carries the
    // full breakdown for the mixed case. Insertion-ordered, so the first currency encountered
    // (newest invoice first) is the one promoted to the top level.
    Map<String, BigDecimal> outstandingByCurrency = new LinkedHashMap<>();
    for (Invoice invoice : all) {
      outstandingByCurrency.merge(currencyOf(invoice), amount(invoice.getOutstandingAmount()),
          BigDecimal::add);
    }

    JSONArray rows = new JSONArray();
    int from = Math.min(offset, all.size());
    int to = Math.min(from + limit, all.size());
    for (Invoice invoice : all.subList(from, to)) {
      rows.put(toInvoiceJson(invoice));
    }

    JSONObject body = new JSONObject();
    body.put("invoices", rows);
    // Paging metadata. `totalCount` is what makes the page interpretable — without it the browser
    // cannot tell a short last page from the end of the list, and `invoices.length` stops being the
    // count it used to be. Kept additive: a caller that ignores these three keys still renders the
    // first page correctly.
    body.put("totalCount", all.size());
    body.put(PARAM_OFFSET, from);
    body.put(PARAM_LIMIT, limit);
    // No `status`, by contract: the browser derives it by comparing outstandingAmount against
    // grandTotalAmount. That makes PortalInvoiceQuery's docstatus = 'CO' filter load-bearing — the
    // browser makes no scoping or eligibility decision at all.
    body.put(FIELD_CURRENCY, primaryCurrency(outstandingByCurrency));
    body.put(FIELD_OUTSTANDING_AMOUNT, primaryOutstanding(outstandingByCurrency));
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
   * {@link NeoAttachmentsDownloader#handleDownload} streams it; both are the public entry points that
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
    Optional<PortalSession> session = linkService.validate(resolveToken(request)).getSession();
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
    NeoAttachmentsDownloader.handleDownload(attachmentId, response);
    PortalUsageEvents.downloaded(validated, invoice.getId());
  }

  /** The home's balances, per-kind tiles and recent documents ({@link PortalDocumentsEndpoint}). */
  private void handleHome(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    Optional<PortalSession> session = linkService.validate(resolveToken(request)).getSession();
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    sendJson(response, HttpServletResponse.SC_OK, PortalDocumentsEndpoint.home(session.get()));
  }

  /** One page of one document kind; an unknown {@code type} answers 404. */
  private void handleDocuments(HttpServletRequest request, HttpServletResponse response)
      throws IOException, JSONException {
    Optional<PortalSession> session = linkService.validate(resolveToken(request)).getSession();
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    JSONObject body = PortalDocumentsEndpoint.list(session.get(), request.getParameter("type"),
        request.getParameter("status"), request.getParameter("dateFrom"),
        request.getParameter("dateTo"), request.getParameter("q"),
        request.getParameter(PARAM_LIMIT), request.getParameter(PARAM_OFFSET));
    if (body == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    sendJson(response, HttpServletResponse.SC_OK, body);
  }

  /**
   * {@code {kind}/{id}} answers the document's detail and {@code {kind}/{id}/pdf} streams its
   * printable: the main attachment, the same file the email's download link serves (plan §B.7).
   * Anything out of scope, hidden, mistyped or without a printable answers 404.
   */
  private void handleDocument(HttpServletRequest request, HttpServletResponse response,
      String rest) throws IOException, JSONException {
    Optional<PortalSession> session = linkService.validate(resolveToken(request)).getSession();
    if (!session.isPresent()) {
      rejectInvalidLink(response);
      return;
    }
    String[] parts = rest.split("/");
    boolean pdf = parts.length == 3 && PDF_SUFFIX.equals("/" + parts[2]);
    if (parts.length != 2 && !pdf) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    PortalSession validated = session.get();
    if (!pdf) {
      JSONObject body = PortalDocumentsEndpoint.detail(validated, parts[0], parts[1]);
      if (body == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
        return;
      }
      sendJson(response, HttpServletResponse.SC_OK, body);
      return;
    }
    PortalDocumentKind kind = PortalDocumentsEndpoint.kindOf(parts[0]);
    String attachmentId = kind == null
        || PortalDocumentQuery.findInScope(validated, kind, parts[1]) == null ? null
        : PortalDocumentsEndpoint.mainAttachmentId(validated, kind, parts[1]);
    if (attachmentId == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
      return;
    }
    NeoAttachmentsDownloader.handleDownload(attachmentId, response);
    PortalUsageEvents.downloaded(validated, parts[1]);
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
   * Reads the portal token: the {@value #HEADER_PORTAL_TOKEN} header first, then the trailing path
   * segment of {@code /me/{token}}, then an {@code Authorization: Bearer} header.
   *
   * <p><b>The portal token has its own header because it is not a session credential</b>
   * (ETP-6209 live test). The SPA's request helper decides {@code Authorization} from the app's
   * own credential scheme: under the cookie scheme — any browser holding an Etendo GO session —
   * it drops an explicit bearer and sends the session cookie instead, so the portal received no
   * token and answered "this link is no longer valid" to a valid link. A dedicated header is
   * never touched by that scheme, and reading it first also means a GO session's own
   * {@code Authorization} (a bearer JWT) can never be mistaken for a portal token: the portal is
   * authenticated by the link, never by whoever happens to be signed in to GO in the browser.
   *
   * <p>The header and {@code Authorization} keep the token out of access logs and
   * {@code Referer}; the path form exists for a first load made without headers (plan §4.4).
   * {@code Authorization} stays last, for a caller that predates the dedicated header.
   */
  private String resolveToken(HttpServletRequest request) {
    String portalHeader = StringUtils.trimToNull(request.getHeader(HEADER_PORTAL_TOKEN));
    if (portalHeader != null) {
      return portalHeader;
    }
    String path = StringUtils.defaultString(request.getPathInfo());
    if (path.startsWith(PATH_ME + "/")) {
      return StringUtils.trimToNull(path.substring(PATH_ME.length() + 1));
    }
    String header = StringUtils.trimToNull(request.getHeader(HEADER_AUTHORIZATION));
    if (header != null && StringUtils.startsWithIgnoreCase(header, BEARER_PREFIX)) {
      return StringUtils.trimToNull(header.substring(BEARER_PREFIX.length()));
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
    json.put(FIELD_CURRENCY, currencyOf(invoice));
    json.put("grandTotalAmount", amount(invoice.getGrandTotalAmount()));
    json.put(FIELD_OUTSTANDING_AMOUNT, amount(invoice.getOutstandingAmount()));
    return json;
  }

  private static JSONArray toBalancesJson(Map<String, BigDecimal> outstandingByCurrency)
      throws JSONException {
    JSONArray balances = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : outstandingByCurrency.entrySet()) {
      JSONObject balance = new JSONObject();
      balance.put(FIELD_CURRENCY, entry.getKey());
      balance.put(FIELD_OUTSTANDING_AMOUNT, entry.getValue());
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

  /**
   * The single response for every rejected token: unknown, pending, deactivated, malformed or
   * absent.
   */
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

  /**
   * Resolves the requested page size into {@code [1, MAX_LIMIT]}.
   *
   * <p>Anything unusable — absent, blank, non-numeric, zero or negative — becomes
   * {@value #DEFAULT_LIMIT} rather than an error. A malformed paging parameter is not worth failing
   * a customer's page over, and there is no security decision here to get wrong: the scope is fixed
   * by the token, so this only decides how much of an already-authorised list is serialized.
   */
  private static int clampLimit(String raw) {
    int requested = parsePositiveInt(raw, DEFAULT_LIMIT);
    return Math.min(Math.max(requested, 1), MAX_LIMIT);
  }

  /** Resolves the requested offset, treating anything unusable as the first page. */
  private static int parseOffset(String raw) {
    return Math.max(parsePositiveInt(raw, 0), 0);
  }

  private static int parsePositiveInt(String raw, int fallback) {
    String normalized = StringUtils.trimToNull(raw);
    if (normalized == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(normalized);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static void sendJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body.toString());
  }
}
