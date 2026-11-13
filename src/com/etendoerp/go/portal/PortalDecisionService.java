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
  /** The fixed reject reasons (PO spec); {@code OTHER} requires a text. */
  public static final List<String> REASON_CODES = List.of("PRICE", "DELIVERY", "PAYMENT_TERMS",
      "OTHER_SUPPLIER", "NOT_NEEDED", "OTHER");
  public static final String REASON_OTHER = "OTHER";
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
    private final String maskedEmail;

    RequestResult(RequestOutcome outcome, Blocked blocked, String maskedEmail) {
      this.outcome = outcome;
      this.blocked = blocked;
      this.maskedEmail = maskedEmail;
    }

    public RequestOutcome getOutcome() {
      return outcome;
    }

    public Blocked getBlocked() {
      return blocked;
    }

    public String getMaskedEmail() {
      return maskedEmail;
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
    String decision;
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
      return decision;
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

  /** What the landing shows: the state and, unless invalid, the decision and its quotation. */
  public static final class View {
    private final State state;
    private final Decision decision;
    private final Order quotation;

    View(State state, Decision decision, Order quotation) {
      this.state = state;
      this.decision = decision;
      this.quotation = quotation;
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
  }

  private final PortalLinkService linkService;

  public PortalDecisionService() {
    this(new PortalLinkService());
  }

  PortalDecisionService(PortalLinkService linkService) {
    this.linkService = linkService;
  }

  // ─── Request ─────────────────────────────────────────────────────────────

  /**
   * Why the session's quotation cannot be decided now, or {@code null} when it can. The detail's
   * {@code decision} block and {@link #request} share it. Admin mode by the caller.
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
    if (!DECISION_ACCEPT.equals(normalizedDecision) && !DECISION_REJECT.equals(normalizedDecision)
        || DECISION_REJECT.equals(normalizedDecision) && (!REASON_CODES.contains(reason)
            || REASON_OTHER.equals(reason) && text == null)
        || text != null && text.length() > MAX_REASON_TEXT) {
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
    if (inCooldown(quotation.getId())) {
      return new RequestResult(RequestOutcome.COOLDOWN, null, null);
    }
    String recipient = confirmationRecipient(session.getRootLinkId());
    String status = sendConfirmation(session, quotation, normalizedDecision,
        DECISION_REJECT.equals(normalizedDecision) ? reason : null,
        DECISION_REJECT.equals(normalizedDecision) ? text : null);
    if (TransactionalEmailService.STATUS_SENT.equals(status)) {
      PortalDecisionEvents.requested(session, quotation.getId(), normalizedDecision);
      return new RequestResult(RequestOutcome.SENT, null, mask(recipient));
    }
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
          + " VALUES (:id, :client, :org, 'Y', now(), '0', now(), '0', :order, :link, :decision,"
          + " :reasonCode, :reasonText, :hash, :recipient, :language, '" + STATUS_PENDING + "')")
          .setParameter("id", id)
          .setParameter("client", quotation.getClient().getId())
          .setParameter("org", quotation.getOrganization().getId())
          .setParameter("order", quotationId)
          .setParameter("link", linkId)
          .setParameter("decision", decision)
          .setParameter("reasonCode", reasonCode)
          .setParameter("reasonText", reasonText)
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
   */
  public void markSent(String decisionId) {
    try {
      OBContext.setAdminMode(true);
      try {
        OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_portal_decision"
            + " SET sent_at = now(), updated = now() WHERE etgo_portal_decision_id = :id")
            .setParameter("id", decisionId).executeUpdate();
        OBDal.getInstance().commitAndClose();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (RuntimeException e) {
      log.error("Could not mark the decision {} as sent", decisionId, e);
      OBDal.getInstance().rollbackAndClose();
    }
  }

  // ─── Landing ─────────────────────────────────────────────────────────────

  /** What the landing shows for a token. Never writes. Admin mode by the caller. */
  public View view(String rawToken) {
    Decision decision = findByToken(rawToken);
    if (decision == null) {
      return new View(State.INVALID, null, null);
    }
    Order quotation = OBDal.getInstance().get(Order.class, decision.quotationId);
    return new View(stateOf(decision, quotation), decision, quotation);
  }

  /**
   * Confirms a decision: the first confirmed wins. One transaction, committed here.
   *
   * @return the resulting view: {@code CONFIRMED} when this call applied it, else the state found
   */
  public View confirm(String rawToken) {
    Decision decision = findByToken(rawToken);
    if (decision == null) {
      return new View(State.INVALID, null, null);
    }
    List<String> voided = new ArrayList<>();
    View result = SystemContext.call("the portal quotation decision", () -> {
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
        State blocked = !QuotationDecisions.isDecidable(quotation) ? State.NOT_PENDING
            : isExpired(quotation) ? State.EXPIRED : null;
        if (blocked != null) {
          setStatus(current.id, STATUS_NOT_APPLIED, "closed_at");
          OBDal.getInstance().commitAndClose();
          PortalDecisionEvents.notApplied(current.id, current.quotationId, blocked.getCode());
          return new View(blocked, current, quotation);
        }
        apply(current, quotation);
        setStatus(current.id, STATUS_CONFIRMED, "confirmed_at");
        voided.addAll(voidOthers(current));
        OBDal.getInstance().commitAndClose();
        current.status = STATUS_CONFIRMED;
        return new View(State.CONFIRMED, current, quotation);
      } catch (RuntimeException e) {
        OBDal.getInstance().rollbackAndClose();
        throw e;
      }
    });
    if (result.getState() == State.CONFIRMED) {
      PortalDecisionEvents.confirmed(decision.id, decision.quotationId, decision.decision);
      if (!voided.isEmpty()) {
        PortalDecisionEvents.voided(decision.quotationId, voided.size());
      }
      sendNotice(result.getDecision());
    }
    return result;
  }

  /** Applies the confirmed decision to the quotation, through the app's own writes. */
  private void apply(Decision decision, Order quotation) {
    if (DECISION_ACCEPT.equals(decision.decision)) {
      // Unreachable while ACCEPT_AVAILABLE is false: request() refuses accepts (PO question Q1).
      QuotationDecisions.accept(quotation);
      return;
    }
    // The portal's reason is never mapped to the tenant's C_Reject_Reason (PO decision, ETP-6209):
    // a tenant's reasons can be internal ("deudor") and must never reach the customer, and a fixed
    // portal reason, "Otros" included, never creates one. The reason and its text go to the notes;
    // the seller sets the internal reason on the rejected quotation afterwards, if any.
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

  /** A decision by id, for the contracts. Admin mode by the caller. */
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
    decision.decision = (String) row[4];
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

  private static boolean inCooldown(String quotationId) {
    Instant since = Instant.now().minus(PortalLinkConfig.decisionCooldown());
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery("SELECT count(*)"
        + " FROM etgo_portal_decision WHERE c_order_id = :id AND created > :since")
        .setParameter("id", quotationId).setParameter("since", Timestamp.from(since)).list();
    return !rows.isEmpty() && ((Number) rows.get(0)).longValue() > 0;
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

  /** {@code ju•••@example.com}: enough for the customer to recognise it, not to read it. */
  static String mask(String email) {
    String value = StringUtils.trimToEmpty(email);
    int at = value.indexOf('@');
    if (at <= 0) {
      return "";
    }
    String local = value.substring(0, at);
    String shown = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
    return shown + "•••" + value.substring(at);
  }
}
