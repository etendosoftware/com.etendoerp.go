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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.QuotationDecisions;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/**
 * Accept / reject a quotation from the customer portal, confirmed by email (ETP-6209 Part C, plan
 * "Part C").
 *
 * <ol>
 *   <li><b>Request</b> ({@link #request}): the customer, inside a valid portal session, asks to
 *       accept or reject a quotation of their scope. The quotation must be decidable
 *       ({@link QuotationDecisions#isDecidable}, Under evaluation: PO answer to Q3), not expired (no
 *       {@code ValidUntil} = not expired, user decision) and the link's root send must have a To
 *       recipient. A per-quotation cool-down keeps the button from flooding the customer's inbox.
 *       The confirmation email goes to the <b>first To</b> of the root send only, never CC (user
 *       decision): one confirmer, no race between several people of the customer.</li>
 *   <li><b>Mint</b> ({@link #mint}, from the confirmation contract's {@code resolve}): one
 *       {@code ETGO_PORTAL_DECISION} row, pending until its email leaves ({@link #markSent}, from
 *       {@code onDelivered}) — Part A's pending-until-sent rule, so a failed send leaves no usable
 *       token. Only the SHA-256 of the token is stored; the token is only in the email.</li>
 *   <li><b>View</b> ({@link #view}): what the landing shows. Never writes: opening the link never
 *       applies anything (mail scanners pre-fetch links; user decision).</li>
 *   <li><b>Confirm</b> ({@link #confirm}): one transaction — the quotation row is locked
 *       ({@code FOR UPDATE}, the renewal's W1 pattern) and then the decision row; a decision not
 *       pending answers its own state again (single use, never expires); the quotation is
 *       re-checked (decidable, not expired); the first confirmed wins: it is applied, and every other
 *       pending request of the quotation is voided. The seller's notice and the KPI hook run after
 *       the commit.</li>
 * </ol>
 *
 * <p><b>Writes as System.</b> A portal request has no session; the confirmation writes the
 * quotation through the sales-quotation customization ({@link QuotationDecisions}) inside
 * {@link SystemContext}, so the quotation's audit shows System as its updater — the customer is
 * not an Etendo user, and impersonating the seller would be false.</p>
 */
public class PortalDecisionService {

  private static final Logger log = LogManager.getLogger(PortalDecisionService.class);

  /** The confirmation email's contract. */
  public static final String CONFIRM_CONTRACT = "portal-quotation-decision-confirm";
  /** The seller's notice email's contract. */
  public static final String NOTICE_CONTRACT = "portal-quotation-decision-notice";
  /** Command fields of both contracts. */
  public static final String FIELD_DECISION_ID = "decisionId";
  public static final String FIELD_DECISION = "decision";
  public static final String FIELD_REASON_CODE = "reasonCode";
  public static final String FIELD_REASON_TEXT = "reasonText";

  public static final String DECISION_ACCEPT = "ACCEPT";
  public static final String DECISION_REJECT = "REJECT";
  public static final String REASON_OTHER = "OTHER";
  /** The fixed reject reasons (PO spec); {@code OTHER} requires a text. */
  public static final List<String> REASON_CODES = List.of("PRICE", "DELIVERY", "PAYMENT_TERMS",
      "OTHER_SUPPLIER", "NOT_NEEDED", REASON_OTHER);
  static final int MAX_REASON_TEXT = 2000;

  /**
   * Whether the portal offers "Aceptar" (PO answer to Q1, ETP-6209: a confirmed accept moves the
   * quotation to {@link QuotationDecisions#STATUS_ACCEPTED}, which the app can still convert). Kept
   * as a switch: {@code false} refuses accepts with {@code accept_unavailable} and hides the button
   * through the detail's {@code acceptAvailable}.
   */
  public static final boolean ACCEPT_AVAILABLE = true;

  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_CONFIRMED = "CONFIRMED";
  static final String STATUS_VOIDED = "VOIDED";
  static final String STATUS_NOT_APPLIED = "NOT_APPLIED";
  private static final String DECISION_ROUTE = "portal/decision";

  /** Why a quotation cannot be decided now, as the API names it. */
  public enum Blocked {
    NOT_PENDING("not_pending"), EXPIRED("expired"), NO_RECIPIENT("no_recipient");

    private final String code;

    Blocked(String code) {
      this.code = code;
    }

    public String getCode() {
      return code;
    }
  }

  /** What a request ended in. */
  public enum RequestOutcome {
    SENT, NOT_FOUND, BLOCKED, ACCEPT_UNAVAILABLE, INVALID_REASON, COOLDOWN, FAILED
  }

  /** The answer to {@link #request}. */
  public static final class RequestResult {
    private final RequestOutcome outcome;
    private final Blocked blocked;
    private final String email;

    RequestResult(RequestOutcome outcome, Blocked blocked, String email) {
      this.outcome = outcome;
      this.blocked = blocked;
      this.email = email;
    }

    public RequestOutcome getOutcome() {
      return outcome;
    }

    public Blocked getBlocked() {
      return blocked;
    }

    /** The confirmation's recipient, in full (design decision, Figma). */
    public String getEmail() {
      return email;
    }
  }

  /** A minted decision: its row and the confirmation URL its email carries. */
  public static final class MintedDecision {
    private final String id;
    private final String url;

    MintedDecision(String id, String url) {
      this.id = id;
      this.url = url;
    }

    public String getId() {
      return id;
    }

    public String getUrl() {
      return url;
    }
  }

  /** One decision row, as the landing and the emails read it. */
  public static final class Decision {
    String id;
    String clientId;
    String quotationId;
    String linkId;
    /** {@code ACCEPT} or {@code REJECT}. */
    String action;
    String reasonCode;
    String reasonText;
    String recipientEmail;
    String language;
    String status;
    boolean sent;

    public String getId() {
      return id;
    }

    public String getClientId() {
      return clientId;
    }

    public String getQuotationId() {
      return quotationId;
    }

    public String getLinkId() {
      return linkId;
    }

    public String getDecision() {
      return action;
    }

    public String getReasonCode() {
      return reasonCode;
    }

    public String getReasonText() {
      return reasonText;
    }

    public String getRecipientEmail() {
      return recipientEmail;
    }

    public String getLanguage() {
      return language;
    }

    public String getStatus() {
      return status;
    }
  }

  /** The landing's states (the API's {@code state}). */
  public enum State {
    PENDING("pending"), CONFIRMED("confirmed"), VOIDED("voided"), NOT_PENDING("not_pending"),
    EXPIRED("expired"), INVALID("invalid");

    private final String code;

    State(String code) {
      this.code = code;
    }

    public String getCode() {
      return code;
    }
  }

  /**
   * The quotation's figures the landing shows, read while the DAL session is open. A confirmation
   * commits and closes its session before answering, which detaches the quotation: reading a lazy
   * association then (its currency) threw {@code LazyInitializationException} and answered 500 to
   * every applying confirmation (ETP-6209 live test).
   */
  public static final class Summary {
    private final String documentNo;
    private final Date date;
    private final BigDecimal grandTotal;
    private final String currency;
    private final String documentStatus;

    private Summary(Order quotation) {
      this.documentNo = quotation.getDocumentNo();
      this.date = quotation.getOrderDate();
      this.grandTotal = quotation.getGrandTotalAmount();
      this.currency = quotation.getCurrency() == null ? null
          : quotation.getCurrency().getISOCode();
      this.documentStatus = quotation.getDocumentStatus();
    }

    /** The quotation's figures, or {@code null} without a quotation. Needs an open session. */
    static Summary of(Order quotation) {
      return quotation == null ? null : new Summary(quotation);
    }

    public String getDocumentNo() {
      return documentNo;
    }

    public Date getDate() {
      return date;
    }

    public BigDecimal getGrandTotal() {
      return grandTotal;
    }

    public String getCurrency() {
      return currency;
    }

    public String getDocumentStatus() {
      return documentStatus;
    }
  }

  /** What the landing shows: the state and, unless invalid, the decision and its quotation. */
  public static final class View {
    private final State state;
    private final Decision decision;
    private final Order quotation;
    private final boolean appliedNow;
    private final boolean customerClosed;
    /** Read before the session closed, or {@code null} to read the quotation when asked. */
    private final Summary summary;

    View(State state, Decision decision, Order quotation) {
      this(state, decision, quotation, false);
    }

    View(State state, Decision decision, Order quotation, boolean appliedNow) {
      this(state, decision, quotation, appliedNow, false);
    }

    private View(State state, Decision decision, Order quotation, boolean appliedNow,
        boolean customerClosed) {
      this(state, decision, quotation, appliedNow, customerClosed, null);
    }

    private View(State state, Decision decision, Order quotation, boolean appliedNow,
        boolean customerClosed, Summary summary) {
      this.state = state;
      this.decision = decision;
      this.quotation = quotation;
      this.appliedNow = appliedNow;
      this.customerClosed = customerClosed;
      this.summary = summary;
    }

    /**
     * A view whose summary is read now, for an answer given after the session closes (the
     * confirmation's transaction commits and closes before the landing reads it).
     */
    static View beforeSessionCloses(State state, Decision decision, Order quotation,
        boolean appliedNow) {
      return new View(state, decision, quotation, appliedNow, false, Summary.of(quotation));
    }

    /** The answer when the portal is closed for the customer: nothing applied, no way in. */
    static View closedForCustomer(Decision decision, Order quotation) {
      return new View(State.NOT_PENDING, decision, quotation, false, true);
    }

    /**
     * Whether the portal's access checks closed it for this customer (blocked or inactive
     * customer, expired plan). Such an answer never leads into the portal.
     *
     * @return {@code true} when closed for the customer
     */
    public boolean isCustomerClosed() {
      return customerClosed;
    }

    /**
     * Whether this very call applied the decision; {@code false} when it was already confirmed
     * (the email link opened again), so the landing can say "ya estaba aplicada".
     */
    public boolean isAppliedNow() {
      return appliedNow;
    }

    public State getState() {
      return state;
    }

    public Decision getDecision() {
      return decision;
    }

    public Order getQuotation() {
      return quotation;
    }

    /** The quotation's figures: the ones read before the session closed, else read now. */
    public Summary getSummary() {
      return summary != null ? summary : Summary.of(quotation);
    }
  }

  private final PortalLinkService linkService;
  private final PortalAccessChecks accessChecks;

  /** The service on the portal's own link service and access checks. */
  public PortalDecisionService() {
    this(new PortalLinkService());
  }

  PortalDecisionService(PortalLinkService linkService) {
    this(linkService, new PortalAccessChecks());
  }

  PortalDecisionService(PortalLinkService linkService, PortalAccessChecks accessChecks) {
    this.linkService = linkService;
    this.accessChecks = accessChecks;
  }

  // ─── Request ─────────────────────────────────────────────────────────────

  /**
   * Why the session's quotation cannot be decided now, or {@code null} when it can. The detail's
   * {@code decision} block and {@link #request} share it. Admin mode by the caller.
   *
   * @param session the validated session (its root send names the confirmation's recipient)
   * @param quotation the quotation, already in the session's scope
   * @return the reason, or {@code null} when the quotation can be decided
   */
  public Blocked blockedReason(PortalSession session, Order quotation) {
    if (!QuotationDecisions.isDecidable(quotation)) {
      return Blocked.NOT_PENDING;
    }
    if (isExpired(quotation)) {
      return Blocked.EXPIRED;
    }
    return confirmationRecipient(session.getRootLinkId()) == null ? Blocked.NO_RECIPIENT : null;
  }

  /**
   * Asks to accept or reject a quotation of the session's scope and emails the confirmation.
   *
   * @param session the validated session
   * @param quotationId the quotation
   * @param decision {@code ACCEPT} or {@code REJECT}
   * @param reasonCode one of {@link #REASON_CODES} (reject only)
   * @param reasonText required for {@code OTHER}, at most {@value #MAX_REASON_TEXT} characters
   * @return the outcome, with the recipient when the email left
   */
  public RequestResult request(PortalSession session, String quotationId, String decision,
      String reasonCode, String reasonText) {
    String normalizedDecision = StringUtils.upperCase(StringUtils.trimToNull(decision));
    if (DECISION_ACCEPT.equals(normalizedDecision) && !ACCEPT_AVAILABLE) {
      return new RequestResult(RequestOutcome.ACCEPT_UNAVAILABLE, null, null);
    }
    String reason = StringUtils.upperCase(StringUtils.trimToNull(reasonCode));
    // Free text belongs to "Otros" only (PO spec): with any other reason it is discarded, so a text
    // typed before switching reason is never stored, emailed or noted.
    String text = REASON_OTHER.equals(reason) ? StringUtils.trimToNull(reasonText) : null;
    if (!isWellFormed(normalizedDecision, reason, text)) {
      return new RequestResult(RequestOutcome.INVALID_REASON, null, null);
    }
    BaseOBObject document = PortalDocumentQuery.findInScope(session, PortalDocumentKind.QUOTATION,
        quotationId);
    if (!(document instanceof Order)) {
      return new RequestResult(RequestOutcome.NOT_FOUND, null, null);
    }
    Order quotation = (Order) document;
    Blocked blocked = blockedReason(session, quotation);
    if (blocked != null) {
      return new RequestResult(RequestOutcome.BLOCKED, blocked, null);
    }
    // Serializes concurrent requests on the quotation (the renewal's W1 pattern): held until this
    // request's transaction ends, so two simultaneous clicks cannot both pass the cool-down and send.
    lock("SELECT c_order_id FROM c_order WHERE c_order_id = :id FOR UPDATE", quotation.getId());
    if (inCooldown(quotation.getId())) {
      return new RequestResult(RequestOutcome.COOLDOWN, null, null);
    }
    boolean reject = DECISION_REJECT.equals(normalizedDecision);
    return deliverConfirmation(session, quotation, normalizedDecision, reject ? reason : null,
        reject ? text : null);
  }

  /** A known decision; a reject with a known reason, and a text (within its limit) for "Otros". */
  private static boolean isWellFormed(String decision, String reason, String text) {
    if (!DECISION_ACCEPT.equals(decision) && !DECISION_REJECT.equals(decision)) {
      return false;
    }
    if (DECISION_REJECT.equals(decision)
        && (reason == null || !REASON_CODES.contains(reason)
            || REASON_OTHER.equals(reason) && text == null)) {
      return false;
    }
    return text == null || text.length() <= MAX_REASON_TEXT;
  }

  /**
   * Sends the confirmation email under the quotation lock and maps its status to the outcome; a
   * request whose email did not leave stops counting against the cool-down.
   */
  private RequestResult deliverConfirmation(PortalSession session, Order quotation,
      String normalizedDecision, String reason, String text) {
    String recipient = confirmationRecipient(session.getRootLinkId());
    String status = sendConfirmation(session, quotation, normalizedDecision, reason, text);
    if (TransactionalEmailService.STATUS_SENT.equals(status)) {
      PortalDecisionEvents.requested(session, quotation.getId(), normalizedDecision);
      return new RequestResult(RequestOutcome.SENT, null,
          StringUtils.trimToEmpty(recipient));
    }
    // The email did not leave: its pending row must not count against the cool-down (Part A's
    // renewal rule), so a provider failure does not lock the customer out for the whole window.
    deactivatePendingOf(quotation.getId());
    if (TransactionalEmailService.STATUS_THROTTLED.equals(status)
        || TransactionalEmailService.STATUS_DUPLICATE.equals(status)) {
      return new RequestResult(RequestOutcome.COOLDOWN, null, null);
    }
    log.warn("The decision confirmation for quotation {} was not sent ({})", quotation.getId(),
        status);
    return new RequestResult(RequestOutcome.FAILED, null, null);
  }

  /**
   * The confirmation's recipient: the first valid To of the root send (user decision), never CC.
   *
   * @param rootLinkId the session's root link
   * @return the address, or {@code null}
   */
  public String confirmationRecipient(String rootLinkId) {
    Optional<PortalLink> root = rootLinkId == null ? Optional.empty()
        : linkService.findLink(rootLinkId);
    if (!root.isPresent()) {
      return null;
    }
    for (String address : root.get().getRecipientsTo()) {
      if (EmailContractCommandSupport.isValidEmail(address)) {
        return address.trim();
      }
    }
    return null;
  }

  /**
   * Mints the pending decision the confirmation email carries. Called from the confirmation
   * contract's {@code resolve}, inside {@link PortalDecisionScope}.
   *
   * @param linkId the session's link
   * @param quotationId the quotation
   * @param decision {@code ACCEPT} or {@code REJECT}
   * @param reasonCode the reject reason, or {@code null}
   * @param reasonText the "Otros" text, or {@code null}
   * @param recipient the confirmation's recipient, for audit
   * @param language the email's language
   * @return the minted row and its URL; empty when the quotation is gone or no app URL is set
   */
  public Optional<MintedDecision> mint(String linkId, String quotationId, String decision,
      String reasonCode, String reasonText, String recipient, String language) {
    String token = PortalTokens.newToken();
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (StringUtils.isBlank(baseUrl)) {
      log.warn("A decision confirmation was requested but {} is not configured",
          PublicUrlResolver.APP_BASE_URL_PROPERTY);
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      Order quotation = OBDal.getInstance().get(Order.class, quotationId);
      if (quotation == null) {
        return Optional.empty();
      }
      String id = SequenceIdData.getUUID();
      OBDal.getInstance().getSession().createNativeQuery("INSERT INTO etgo_portal_decision"
          + " (etgo_portal_decision_id, ad_client_id, ad_org_id, isactive, created, createdby,"
          + " updated, updatedby, c_order_id, etgo_portal_link_id, decision, reason_code,"
          + " reason_text, token_hash, recipient_email, email_language, status)"
          + " VALUES (:id, :client, :org, 'Y', now(), '0', now(), '0', :order, :link, :action,"
          + " :code, :text, :hash, :recipient, :language, '" + STATUS_PENDING + "')")
          .setParameter("id", id)
          .setParameter("client", quotation.getClient().getId())
          .setParameter("org", quotation.getOrganization().getId())
          .setParameter("order", quotationId)
          .setParameter("link", linkId)
          .setParameter("action", decision)
          .setParameter("code", reasonCode)
          .setParameter("text", reasonText)
          .setParameter("hash", PortalTokens.hash(token))
          .setParameter("recipient", recipient)
          .setParameter("language", StringUtils.defaultIfBlank(language, "es_ES"))
          .executeUpdate();
      return Optional.of(new MintedDecision(id,
          PublicUrlResolver.appendPath(baseUrl, DECISION_ROUTE + "/" + token)));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The confirmation email left: the decision's token becomes usable. Committed at once, like a
   * link's confirmation (Part A): the customer holds the email whatever happens next.
   *
   * @param decisionId the decision whose email was delivered
   */
  public void markSent(String decisionId) {
    try {
      Decision decision;
      OBContext.setAdminMode(true);
      try {
        // Read before the commit closes the session: the KPI below needs its action and tenant.
        decision = findById(decisionId);
        OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_portal_decision"
            + " SET sent_at = now(), updated = now() WHERE etgo_portal_decision_id = :id")
            .setParameter("id", decisionId).executeUpdate();
        OBDal.getInstance().commitAndClose();
      } finally {
        OBContext.restorePreviousMode();
      }
      if (decision != null) {
        PortalKpiTelemetry.decisionEmailSent(DECISION_ACCEPT.equals(decision.action),
            decision.clientId);
      }
    } catch (RuntimeException e) {
      log.error("Could not mark the decision {} as sent", decisionId, e);
      OBDal.getInstance().rollbackAndClose();
    }
  }

  // ─── Landing ─────────────────────────────────────────────────────────────

  /**
   * What the landing shows for a token. Never writes. Admin mode by the caller.
   *
   * @param rawToken the token of the email's link
   * @return the decision's state, or {@code INVALID}
   */
  public View view(String rawToken) {
    Decision decision = findByToken(rawToken);
    if (decision == null) {
      return new View(State.INVALID, null, null);
    }
    Order quotation = OBDal.getInstance().get(Order.class, decision.quotationId);
    return new View(stateOf(decision, quotation), decision, quotation);
  }

  /**
   * Confirms a decision: the first confirmed wins. One transaction, committed here. Reached by the
   * landing's automatic POST when the customer opens the email's button (one step, ETP-6209 call
   * with the PO): the page's script sends it on load, so a link scanner that only GETs the URL
   * applies nothing.
   *
   * @param rawToken the token of the email's link
   * @return the resulting view: {@code CONFIRMED} with {@link View#isAppliedNow} when this call
   *     applied it, else the state found (an already confirmed decision answers {@code CONFIRMED}
   *     without {@code appliedNow})
   */
  public View confirm(String rawToken) {
    Decision decision = findByToken(rawToken);
    if (decision == null) {
      return new View(State.INVALID, null, null);
    }
    View closed = closedForCustomer(decision);
    if (closed != null) {
      return closed;
    }
    List<String> voided = new ArrayList<>();
    View result = SystemContext.call("the portal quotation decision",
        () -> confirmInTransaction(decision, voided));
    if (result.isAppliedNow()) {
      PortalDecisionEvents.confirmed(decision.id, decision.quotationId, decision.action);
      PortalKpiTelemetry.portalQuotationDecided(DECISION_ACCEPT.equals(decision.action),
          decision.clientId);
      if (!voided.isEmpty()) {
        PortalDecisionEvents.voided(decision.quotationId, voided.size());
      }
      sendNotice(result.getDecision());
    }
    return result;
  }

  /**
   * The confirmation's transaction (the first confirmed wins): locks the quotation, then the
   * decision row, re-checks both and applies; committed or rolled back here.
   *
   * @param decision the decision the token named
   * @param voided collects the ids of the other pending requests this confirmation voided
   * @return the resulting view
   */
  private View confirmInTransaction(Decision decision, List<String> voided) {
    try {
      lock("SELECT c_order_id FROM c_order WHERE c_order_id = :id FOR UPDATE",
          decision.quotationId);
      lock("SELECT etgo_portal_decision_id FROM etgo_portal_decision"
          + " WHERE etgo_portal_decision_id = :id FOR UPDATE", decision.id);
      Decision current = findById(decision.id);
      Order quotation = OBDal.getInstance().get(Order.class, decision.quotationId);
      if (current == null || quotation == null) {
        return new View(State.INVALID, null, null);
      }
      OBDal.getInstance().refresh(quotation);
      if (!STATUS_PENDING.equals(current.status)) {
        return new View(stateOf(current, quotation), current, quotation);
      }
      State blocked = notApplicableState(quotation);
      if (blocked != null) {
        setStatus(current.id, STATUS_NOT_APPLIED, "closed_at");
        View notApplied = View.beforeSessionCloses(blocked, current, quotation, false);
        OBDal.getInstance().commitAndClose();
        PortalDecisionEvents.notApplied(current.id, current.quotationId, blocked.getCode());
        return notApplied;
      }
      apply(current, quotation);
      setStatus(current.id, STATUS_CONFIRMED, "confirmed_at");
      voided.addAll(voidOthers(current));
      current.status = STATUS_CONFIRMED;
      // Read before commitAndClose: the quotation is detached afterwards (lazy currency).
      View applied = View.beforeSessionCloses(State.CONFIRMED, current, quotation, true);
      OBDal.getInstance().commitAndClose();
      return applied;
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    }
  }

  /** Why the quotation can no longer take the decision ({@code null} when it can). */
  private static State notApplicableState(Order quotation) {
    if (!QuotationDecisions.isDecidable(quotation)) {
      return State.NOT_PENDING;
    }
    return isExpired(quotation) ? State.EXPIRED : null;
  }

  /**
   * The portal's access checks at confirm time (blocked or inactive customer, expired plan), for
   * the decision's link: a decision token never expires, so a customer blocked after asking must not
   * still apply it. Closed → {@code NOT_PENDING} ("no se ha aplicado nada"), nothing is written and
   * the row stays pending; the customer is never told why (as {@link PortalAccessChecks}). Fails
   * closed when the link cannot be read.
   *
   * @return the answer when closed, else {@code null}
   */
  private View closedForCustomer(Decision decision) {
    OBContext.setAdminMode(true);
    try {
      Optional<PortalLink> link = linkService.findLink(decision.linkId);
      Optional<String> reason = link.isPresent()
          ? accessChecks.blockReasonOrClosed(link.get().getClientId(),
              link.get().getBusinessPartnerId())
          : Optional.of(PortalAccessChecks.INACTIVE);
      if (!reason.isPresent()) {
        return null;
      }
      log.info("Portal decision {} was not applied: {}", decision.id, reason.get());
      return View.closedForCustomer(decision,
          OBDal.getInstance().get(Order.class, decision.quotationId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Applies the confirmed decision to the quotation, through the app's own writes. */
  private void apply(Decision decision, Order quotation) {
    if (DECISION_ACCEPT.equals(decision.action)) {
      QuotationDecisions.accept(quotation);
      return;
    }
    // The portal's reason is never mapped to the tenant's reject reasons (PO decision, ETP-6209).
    // A tenant's reasons can be internal, such as "deudor", and must never reach the customer, and
    // no fixed portal reason ("Otros" included) ever creates one. The reason and its text go to the
    // notes, and the seller may set the internal reason on the rejected quotation afterwards.
    appendNote(quotation, decision);
    QuotationDecisions.reject(quotation, null);
  }

  /** The customer's reason in the quotation's notes ({@code Description}, the window's notes). */
  private static void appendNote(Order quotation, Decision decision) {
    String reason = EmailMessages.get(
        "portal-quotation-decision.reason." + decision.reasonCode, decision.language);
    String text = StringUtils.trimToNull(decision.reasonText);
    String line = text == null
        ? EmailMessages.get("portal-quotation-decision.note", decision.language, reason)
        : EmailMessages.get("portal-quotation-decision.note.withText", decision.language, reason,
            text);
    String current = StringUtils.trimToNull(quotation.getDescription());
    quotation.setDescription(current == null ? line.trim() : current + "\n" + line.trim());
  }

  // ─── Emails ──────────────────────────────────────────────────────────────

  private static String sendConfirmation(PortalSession session, Order quotation, String decision,
      String reasonCode, String reasonText) {
    JSONObject command = new JSONObject();
    try {
      command.put(PortalLinkService.FIELD_LINK_ID, session.getLinkId());
      command.put(EmailContractCommandSupport.FIELD_RECORD_ID, quotation.getId());
      command.put(EmailContractCommandSupport.FIELD_TENANT_ID, session.getClientId());
      command.put(EmailContractCommandSupport.FIELD_LANGUAGE, session.getLanguage());
      command.put(FIELD_DECISION, decision);
      if (reasonCode != null) {
        command.put(FIELD_REASON_CODE, reasonCode);
      }
      if (reasonText != null) {
        command.put(FIELD_REASON_TEXT, reasonText);
      }
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the decision confirmation command", e);
    }
    return send(CONFIRM_CONTRACT, command);
  }

  /** The seller's notice, after the commit. Never fails the confirmation. */
  private static void sendNotice(Decision decision) {
    try {
      JSONObject command = new JSONObject();
      command.put(FIELD_DECISION_ID, decision.id);
      command.put(EmailContractCommandSupport.FIELD_RECORD_ID, decision.quotationId);
      command.put(EmailContractCommandSupport.FIELD_TENANT_ID, decision.clientId);
      command.put(EmailContractCommandSupport.FIELD_LANGUAGE, decision.language);
      String status = send(NOTICE_CONTRACT, command);
      if (!TransactionalEmailService.STATUS_SENT.equals(status)) {
        log.info("The decision notice for quotation {} was not sent ({})", decision.quotationId,
            status);
      }
    } catch (JSONException | RuntimeException e) {
      log.warn("The decision notice for quotation {} failed: {}", decision.quotationId,
          e.getClass().getSimpleName());
    }
  }

  private static String send(String contract, JSONObject command) {
    return SystemContext.call("the portal quotation decision email",
        () -> PortalDecisionScope.call(() -> statusOf(
            new TransactionalEmailService().send(contract, command))));
  }

  private static String statusOf(NeoResponse response) {
    if (response == null || response.getBody() == null) {
      return null;
    }
    JSONObject data = response.getBody().optJSONObject("response");
    data = data == null ? null : data.optJSONObject("data");
    return data == null ? null : data.optString("status", null);
  }

  /**
   * The seller's notice recipient: the ETGO_ACCOUNT email of the user who sent the quotation (the
   * root link's creator, as the portal's PDF render resolves it). {@code AD_User.Email} is not
   * filled by onboarding, so the account is the identity. {@code null} when it cannot be resolved
   * (a system-created root, an inactive user, no account): no notice, the decision still stands.
   * Admin mode by the caller.
   *
   * @param decision the confirmed decision
   * @return the sender's account email, or {@code null}
   */
  public String senderEmail(Decision decision) {
    String userId = decision == null ? null : PortalPrintable.senderOfLink(decision.linkId);
    if (userId == null) {
      return null;
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null || !Boolean.TRUE.equals(user.isActive())) {
      return null;
    }
    return GoAccountResolver.findAccountByUsername(user.getUsername()).map(Account::getEmail)
        .filter(EmailContractCommandSupport::isValidEmail).orElse(null);
  }

  // ─── Reads and writes ────────────────────────────────────────────────────

  /**
   * A decision by id, for the contracts. Admin mode by the caller.
   *
   * @param decisionId the row's id
   * @return the decision, or {@code null}
   */
  public Decision findById(String decisionId) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(SELECT_DECISION
        + " WHERE etgo_portal_decision_id = :id").setParameter("id", decisionId).list();
    return rows.isEmpty() ? null : toDecision((Object[]) rows.get(0));
  }

  /** A sent decision by its raw token; {@code null} for unknown, blank or not sent. */
  private Decision findByToken(String rawToken) {
    String hash = PortalTokens.hash(StringUtils.trimToNull(rawToken));
    if (hash == null) {
      return null;
    }
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(SELECT_DECISION
        + " WHERE token_hash = :hash AND isactive = 'Y'").setParameter("hash", hash).list();
    Decision decision = rows.isEmpty() ? null : toDecision((Object[]) rows.get(0));
    return decision == null || !decision.sent ? null : decision;
  }

  private static final String SELECT_DECISION = "SELECT etgo_portal_decision_id, ad_client_id,"
      + " c_order_id, etgo_portal_link_id, decision, reason_code, reason_text, recipient_email,"
      + " email_language, status, sent_at FROM etgo_portal_decision";

  private static Decision toDecision(Object[] row) {
    Decision decision = new Decision();
    decision.id = (String) row[0];
    decision.clientId = (String) row[1];
    decision.quotationId = (String) row[2];
    decision.linkId = (String) row[3];
    decision.action = (String) row[4];
    decision.reasonCode = (String) row[5];
    decision.reasonText = (String) row[6];
    decision.recipientEmail = (String) row[7];
    decision.language = (String) row[8];
    decision.status = (String) row[9];
    decision.sent = row[10] != null;
    return decision;
  }

  private static State stateOf(Decision decision, Order quotation) {
    switch (StringUtils.defaultString(decision.status)) {
      case STATUS_CONFIRMED:
        return State.CONFIRMED;
      case STATUS_VOIDED:
        return State.VOIDED;
      case STATUS_NOT_APPLIED:
        return quotation != null && QuotationDecisions.isDecidable(quotation) ? State.EXPIRED
            : State.NOT_PENDING;
      default:
        if (!QuotationDecisions.isDecidable(quotation)) {
          return State.NOT_PENDING;
        }
        return isExpired(quotation) ? State.EXPIRED : State.PENDING;
    }
  }

  /** Expired: a {@code ValidUntil} before today (server day); none = not expired (user decision). */
  static boolean isExpired(Order quotation) {
    Date validUntil = quotation.getValidUntil();
    if (validUntil == null) {
      return false;
    }
    LocalDate day = validUntil instanceof java.sql.Date ? ((java.sql.Date) validUntil).toLocalDate()
        : validUntil.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    return day.isBefore(LocalDate.now());
  }

  /**
   * The per-quotation cool-down: a request created inside the window that was sent or is still in
   * flight counts (an in-flight one closes the race with a concurrent request, which the quotation
   * lock serializes); a request whose email did not leave was deactivated
   * ({@link #deactivatePendingOf}) and does not.
   */
  private static boolean inCooldown(String quotationId) {
    Instant since = Instant.now().minus(PortalLinkConfig.decisionCooldown());
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery("SELECT count(*)"
        + " FROM etgo_portal_decision WHERE c_order_id = :id AND created > :since"
        + " AND isactive = 'Y'")
        .setParameter("id", quotationId).setParameter("since", Timestamp.from(since)).list();
    return !rows.isEmpty() && ((Number) rows.get(0)).longValue() > 0;
  }

  /**
   * Deactivates the quotation's requests whose email never left ({@code SENT_AT} null). Called
   * under the quotation lock, so the only pending row it can meet is this request's own (or an
   * abandoned one, which was never usable). Like {@code PortalLinkDal#deactivatePendingRenewalsOf}.
   */
  private static void deactivatePendingOf(String quotationId) {
    OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_portal_decision"
        + " SET isactive = 'N', updated = now() WHERE c_order_id = :id AND sent_at IS NULL"
        + " AND isactive = 'Y'").setParameter("id", quotationId).executeUpdate();
  }

  private static void lock(String sql, String id) {
    OBDal.getInstance().getSession().createNativeQuery(sql).setParameter("id", id).list();
  }

  private static void setStatus(String decisionId, String status, String timestampColumn) {
    OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_portal_decision SET status = '"
        + status + "', " + timestampColumn + " = now(), updated = now()"
        + " WHERE etgo_portal_decision_id = :id").setParameter("id", decisionId).executeUpdate();
  }

  /** Voids the quotation's other pending requests; answers their ids. */
  @SuppressWarnings("unchecked")
  private static List<String> voidOthers(Decision confirmed) {
    List<String> ids = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT etgo_portal_decision_id FROM etgo_portal_decision WHERE c_order_id = :order"
            + " AND status = '" + STATUS_PENDING + "' AND etgo_portal_decision_id <> :id"
            + " FOR UPDATE")
        .setParameter("order", confirmed.quotationId).setParameter("id", confirmed.id).list();
    if (!ids.isEmpty()) {
      OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_portal_decision"
          + " SET status = '" + STATUS_VOIDED + "', closed_at = now(), updated = now()"
          + " WHERE etgo_portal_decision_id IN (:ids)").setParameterList("ids", ids)
          .executeUpdate();
    }
    return ids;
  }
}
