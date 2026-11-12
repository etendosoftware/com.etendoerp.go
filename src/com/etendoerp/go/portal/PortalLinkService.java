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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Owns the lifecycle of a customer portal link (ETP-6209): one link per document email, valid for
 * a fixed time after it was sent, renewed by email when it is opened after it expired.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li><b>Mint, pending.</b> {@link #mintForSend} runs while the document email is being built
 *       and inserts a row with {@code SENT_AT} null. Its token goes into that email only.</li>
 *   <li><b>Confirm.</b> {@link #confirm} runs only when the email service reports the email left
 *       ({@code EmailContract#onDelivered}). It stamps {@code SENT_AT} and {@code EXPIRES_AT}. A
 *       throttled, duplicate, suppressed or failed send never confirms, so its row stays pending —
 *       invalid forever, and absent from the "sent with link" KPI.</li>
 *   <li><b>Use.</b> {@link #validate} answers valid, expired, unavailable (the customer is
 *       blocked, inactive, or the issuing company's plan expired — decision D-1) or invalid.</li>
 *   <li><b>Renew.</b> {@link #renew} emails a new link to the expired link's own To and CC, never
 *       to whoever opened it.</li>
 * </ol>
 *
 * <p><b>The {@code bp-portal-link} gate is checked inside {@link #mintForSend}, before anything
 * is written</b>, exactly as the MVP did: minting a row while the gate is closed is the failure
 * mode that would leak the feature early. A renewal is not gated: it only replaces a link that the
 * gate already let out.
 *
 * <p><b>Older links are never touched.</b> Minting only inserts; a new send does not invalidate any
 * earlier link (story item 3).
 */
public class PortalLinkService {

  /** The email contract that carries a renewed link. */
  public static final String RENEWAL_CONTRACT = "portal-link-renewal";
  /** Command field carrying the expired link a renewal replaces. */
  public static final String FIELD_LINK_ID = "linkId";

  /** Status of a portal link as seen by whoever holds its token. */
  public enum State {
    /** Unknown, pending, deactivated, malformed or absent — indistinguishable by design. */
    INVALID,
    /** Sent, within its lifetime, and the customer is not blocked. */
    VALID,
    /** Sent and past its lifetime. */
    EXPIRED,
    /** Within its lifetime, but the customer is blocked/inactive or the plan expired. */
    UNAVAILABLE
  }

  /** What a renewal attempt did. */
  public enum RenewalOutcome {
    /** Unknown or unusable token: the same answer as an invalid link. */
    INVALID,
    /** The link has not expired: nothing was sent. */
    NOT_EXPIRED,
    /** A new link was emailed. */
    SENT,
    /** A new link was emailed recently (cool-down, daily limit, or the email service's own). */
    RECENTLY_SENT,
    /** The customer is blocked or inactive, or the issuing company's plan expired. */
    UNAVAILABLE,
    /** The email could not be sent; worth retrying. */
    FAILED
  }

  /** A link minted for an email: its row id and the absolute URL to put in the email. */
  public static final class MintedLink {
    private final String id;
    private final String url;
    private final Duration validity;

    MintedLink(String id, String url, Duration validity) {
      this.id = id;
      this.url = url;
      this.validity = validity;
    }

    public String getId() {
      return id;
    }

    public String getUrl() {
      return url;
    }

    /**
     * @return how long the link will be valid once sent, for the sentence that states it
     */
    public Duration getValidity() {
      return validity;
    }
  }

  /** The outcome of validating a token: its state and, when known, its link. */
  public static final class Validation {
    private final State state;
    private final PortalLink link;

    Validation(State state, PortalLink link) {
      this.state = state;
      this.link = link;
    }

    public State getState() {
      return state;
    }

    /**
     * @return the link, present for every state but {@link State#INVALID}
     */
    public Optional<PortalLink> getLink() {
      return Optional.ofNullable(link);
    }

    /**
     * @return the session, present only for {@link State#VALID}
     */
    public Optional<PortalSession> getSession() {
      return state == State.VALID ? Optional.of(new PortalSession(link)) : Optional.empty();
    }
  }

  private static final Logger log = LogManager.getLogger(PortalLinkService.class);

  private static final String STATUS_PATH_RESPONSE = "response";
  private static final String STATUS_PATH_DATA = "data";
  private static final String STATUS_FIELD = "status";
  private static final String ERROR_COOLDOWN = "COOLDOWN";
  private static final String ERROR_DAILY_LIMIT = "DAILY_LIMIT";
  private static final String ERROR_SEND_FAILED = "SEND_FAILED";
  private static final Duration ONE_DAY = Duration.ofDays(1);

  private final PortalAccessChecks accessChecks;
  private final Function<PortalLink, String> renewalSender;
  private final Clock clock;

  public PortalLinkService() {
    this(new PortalAccessChecks(), PortalLinkService::sendRenewalEmail, Clock.systemUTC());
  }

  PortalLinkService(PortalAccessChecks accessChecks, Function<PortalLink, String> renewalSender,
      Clock clock) {
    this.accessChecks = accessChecks;
    this.renewalSender = renewalSender;
    this.clock = clock;
  }

  /**
   * Mints the pending link a document email will carry.
   *
   * <p>Never fails the send: a closed gate, an unconfigured app base URL, an unresolvable document
   * or any error all mean "no link", never "no email". The document is the point of the email; the
   * link is an addition to it.
   *
   * @param kind the document kind declared by the sending contract
   * @param recordId the document
   * @param recipientsTo every To address of the email
   * @param recipientsCc every CC address of the email
   * @param language the email's language
   * @param contractName the sending contract
   * @return the minted link, or empty when no link goes out
   */
  public Optional<MintedLink> mintForSend(PortalDocumentKind kind, String recordId,
      List<String> recipientsTo, List<String> recipientsCc, String language,
      String contractName) {
    if (kind == null || StringUtils.isBlank(recordId) || recipientsTo == null
        || recipientsTo.isEmpty()) {
      return Optional.empty();
    }
    // The bp-portal-link flag, evaluated for the sending account, is the only gate.
    if (!PortalLinkPolicy.isLinkEnabled()) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      Optional<PortalDocumentKind.Owner> owner = kind.resolveOwner(recordId);
      if (!owner.isPresent()) {
        return Optional.empty();
      }
      PortalLinkDal.NewLink link = new PortalLinkDal.NewLink();
      link.id = SequenceIdData.getUUID();
      link.client = owner.get().getClient();
      link.organization = owner.get().getOrganization();
      link.businessPartner = owner.get().getBusinessPartner();
      link.kind = kind;
      link.recordId = recordId;
      link.origin = PortalLink.ORIGIN_SEND;
      link.rootLinkId = link.id;
      link.contractName = contractName;
      link.recipientsTo = recipientsTo;
      link.recipientsCc = recipientsCc;
      link.language = StringUtils.defaultIfBlank(language, "es_ES");
      return insert(link);
    } catch (RuntimeException e) {
      log.error("Could not mint a portal link for {} {}", kind, recordId, e);
      return Optional.empty();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Mints the pending link a renewal email will carry: same document, Business Partner,
   * recipients and language as the expired link, chained to it.
   *
   * @param expiredLinkId the link that was opened after it expired
   * @return the minted link, or empty when the expired link cannot be read
   */
  public Optional<MintedLink> mintRenewal(String expiredLinkId) {
    OBContext.setAdminMode(true);
    try {
      Optional<PortalLink> expired = findLink(expiredLinkId);
      if (!expired.isPresent() || expired.get().getDocumentKind() == null) {
        return Optional.empty();
      }
      PortalLink source = expired.get();
      PortalLinkDal.NewLink link = new PortalLinkDal.NewLink();
      link.id = SequenceIdData.getUUID();
      link.client = OBDal.getInstance().get(Client.class, source.getClientId());
      link.organization = OBDal.getInstance().get(Organization.class, source.getOrganizationId());
      link.businessPartner = OBDal.getInstance()
          .get(BusinessPartner.class, source.getBusinessPartnerId());
      link.kind = source.getDocumentKind();
      link.recordId = source.getRecordId();
      link.origin = PortalLink.ORIGIN_RENEWAL;
      link.parentLinkId = source.getId();
      link.rootLinkId = source.getRootLinkId();
      link.contractName = RENEWAL_CONTRACT;
      link.recipientsTo = source.getRecipientsTo();
      link.recipientsCc = source.getRecipientsCc();
      link.language = source.getLanguage();
      return insert(link);
    } catch (RuntimeException e) {
      log.error("Could not mint the renewal of portal link {}", expiredLinkId, e);
      return Optional.empty();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Confirms that a pending link's email left, which starts its lifetime, and records the KPI.
   *
   * <p>Called from {@code EmailContract#onDelivered}, i.e. only for a {@code SENT} outcome, after
   * the send's history and audit rows are committed. Never throws into the email service: a failed
   * confirmation leaves the link pending (the customer then sees "invalid") and is logged — the
   * email itself was delivered and must be reported so. A failed write is rolled back on its own
   * ({@link #discardFailedWrite}), so it cannot make the rest of the request fail.
   *
   * <p><b>A successful confirmation is committed at once.</b> The customer already holds the email;
   * whether the link in it works must not depend on what the rest of the request does afterwards.
   * Committing here sweeps in nothing of the send's own, because both portal contracts carry an
   * idempotency key and the DAL safety store has already committed the send's history and audit
   * rows by the time {@code onDelivered} runs.
   *
   * @param linkId the pending link
   * @param sendLogId the {@code etgo_email_send_log} row of that send, may be {@code null}
   * @param recipientCount how many addresses received it
   */
  public void confirm(String linkId, String sendLogId, int recipientCount) {
    OBContext.setAdminMode(true);
    try {
      BaseOBObject row = PortalLinkDal.findById(linkId);
      if (row == null || row.get(PortalLinkDal.PROP_SENT_AT) != null) {
        return;
      }
      Instant now = clock.instant();
      PortalLinkDal.confirm(row, Date.from(now), Date.from(now.plus(PortalLinkConfig.ttl())),
          sendLogId);
      PortalLink link = PortalLinkDal.toLink(row);
      SessionHandler.getInstance().commitAndStart();
      if (PortalLink.ORIGIN_RENEWAL.equals(link.getOrigin())) {
        PortalUsageEvents.renewed(link);
      } else {
        PortalUsageEvents.sent(link, sendLogId, recipientCount);
      }
    } catch (RuntimeException e) {
      log.error("Could not confirm portal link {} after its email was sent", linkId, e);
      discardFailedWrite();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Validates a presented token and, when valid, stamps the link as used.
   *
   * @param rawToken the token from the {@code X-Portal-Token} header or the URL path
   * @return the state; unknown, pending, deactivated and blank tokens are all {@link State#INVALID}
   */
  public Validation validate(String rawToken) {
    Optional<PortalLink> sent = findSentLink(rawToken);
    if (!sent.isPresent()) {
      return new Validation(State.INVALID, null);
    }
    PortalLink link = sent.get();
    if (link.isExpiredAt(clock.instant())) {
      return new Validation(State.EXPIRED, link);
    }
    if (accessChecks.blockReasonOrClosed(link.getClientId(), link.getBusinessPartnerId())
        .isPresent()) {
      return new Validation(State.UNAVAILABLE, link);
    }
    try {
      BaseOBObject row = PortalLinkDal.findById(link.getId());
      if (row != null) {
        PortalLinkDal.touchUsed(row, Date.from(clock.instant()));
      }
    } catch (RuntimeException e) {
      // A usage stamp is bookkeeping: it must never turn a valid link into an error page.
      log.error("Could not stamp portal link {} as used", link.getId(), e);
    }
    return new Validation(State.VALID, link);
  }

  /**
   * Resolves the sent, active link a token belongs to, whatever its expiry — for the branding a
   * page shows even on an expired link (the tenant's name and logo).
   *
   * @param rawToken the presented token
   * @return the link, or empty for anything {@link #validate} calls invalid
   */
  public Optional<PortalLink> findSentLink(String rawToken) {
    String tokenHash = PortalTokens.hash(rawToken);
    if (tokenHash == null) {
      return Optional.empty();
    }
    try {
      BaseOBObject row = PortalLinkDal.findByTokenHash(tokenHash);
      if (row == null) {
        return Optional.empty();
      }
      PortalLink link = PortalLinkDal.toLink(row);
      boolean usable = link.isActive() && link.isSent() && link.getDocumentKind() != null
          && link.getClientId() != null && link.getBusinessPartnerId() != null;
      return usable ? Optional.of(link) : Optional.empty();
    } catch (RuntimeException e) {
      // Never says why. A lookup that fails for an infrastructure reason must look exactly like
      // an unknown token to whoever is holding it.
      log.error("Portal token lookup failed", e);
      return Optional.empty();
    }
  }

  /**
   * Reads a link by id, sent or not, active or not.
   *
   * @param linkId the row id
   * @return the link, or empty
   */
  public Optional<PortalLink> findLink(String linkId) {
    BaseOBObject row = PortalLinkDal.findById(linkId);
    return row == null ? Optional.empty() : Optional.of(PortalLinkDal.toLink(row));
  }

  /**
   * Emails a new link for an expired one, to the expired link's own To and CC.
   *
   * <p>In order: the token must name a sent link; the customer must not be blocked or inactive and
   * the issuing company's plan must be current (story item 5) — checked before expiry, so a closed
   * customer is told "unavailable" whatever the link's age; the link must have expired; the
   * per-link cool-down (15 minutes by default, decided by the PO) and the optional
   * per-Business-Partner daily ceiling (off by default) must allow it. Each refusal is recorded as
   * a {@code portal.link.renewed} error event with its reason.
   *
   * <p><b>The cool-down holds across concurrent requests and across nodes.</b> The expired link's
   * row is locked ({@code SELECT … FOR UPDATE}) before the limits are read, so a second open of the
   * same link waits until the first one's transaction commits. The first commit happens inside the
   * send (the safety store commits a successful send together with the pending renewal row), so the
   * waiting request then counts that renewal — pending rows included, by {@code CREATED} — and
   * answers "recently sent". A renewal whose email did not leave deactivates its pending row before
   * its transaction ends, so a failed attempt never blocks the next one.
   *
   * @param rawToken the expired token the customer opened
   * @return what happened
   */
  public RenewalOutcome renew(String rawToken) {
    Optional<PortalLink> sent = findSentLink(rawToken);
    if (!sent.isPresent()) {
      return RenewalOutcome.INVALID;
    }
    PortalLink expired = sent.get();
    Optional<String> blocked = accessChecks.blockReasonOrClosed(expired.getClientId(),
        expired.getBusinessPartnerId());
    if (blocked.isPresent()) {
      log.info("Portal link {} was not renewed: {}", expired.getId(), blocked.get());
      PortalUsageEvents.renewalRefused(expired, blocked.get());
      return RenewalOutcome.UNAVAILABLE;
    }
    if (!expired.isExpiredAt(clock.instant())) {
      return RenewalOutcome.NOT_EXPIRED;
    }
    // Held until this request's transaction commits; see the method javadoc.
    PortalLinkDal.lockForRenewal(expired.getId());
    Optional<String> limited = limitReached(expired, clock.instant());
    if (limited.isPresent()) {
      PortalUsageEvents.renewalRefused(expired, limited.get());
      return RenewalOutcome.RECENTLY_SENT;
    }
    String status = renewalSender.apply(expired);
    if (TransactionalEmailService.STATUS_SENT.equals(status)) {
      return RenewalOutcome.SENT;
    }
    // The email did not leave: its pending row must not count against the cool-down.
    PortalLinkDal.deactivatePendingRenewalsOf(expired.getId());
    if (TransactionalEmailService.STATUS_DUPLICATE.equals(status)
        || TransactionalEmailService.STATUS_THROTTLED.equals(status)) {
      PortalUsageEvents.renewalRefused(expired, status);
      return RenewalOutcome.RECENTLY_SENT;
    }
    log.warn("Renewal email for portal link {} was not sent: {}", expired.getId(), status);
    PortalUsageEvents.renewalRefused(expired, ERROR_SEND_FAILED);
    return RenewalOutcome.FAILED;
  }

  /**
   * Rolls back the failed confirmation and closes the session; the next DAL call opens a fresh
   * session and transaction. A database-level failure has aborted the PostgreSQL transaction, so
   * clearing the session alone would leave every later statement failing.
   *
   * <p><b>Only safe because both portal contracts ({@code *-send} documents and
   * {@code portal-link-renewal}) carry an idempotency key:</b> the DAL safety store has therefore
   * already committed the send's history and audit rows before {@code onDelivered} — and so this
   * method — runs, and the rollback reaches nothing of the send's own. The caller holds no DAL
   * entity it reads afterwards: the email service only writes its response.
   */
  private static void discardFailedWrite() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.error("Could not roll back the failed portal link write", e);
    }
  }

  private Optional<String> limitReached(PortalLink expired, Instant now) {
    Date cooldownStart = Date.from(now.minus(PortalLinkConfig.renewalCooldown()));
    if (PortalLinkDal.countRenewalsOf(expired.getId(), cooldownStart) > 0) {
      return Optional.of(ERROR_COOLDOWN);
    }
    long maxPerDay = PortalLinkConfig.renewalMaxPerDay();
    if (maxPerDay > 0 && PortalLinkDal.countRenewalsForBusinessPartner(
        expired.getClientId(), expired.getBusinessPartnerId(),
        Date.from(now.minus(ONE_DAY))) >= maxPerDay) {
      return Optional.of(ERROR_DAILY_LIMIT);
    }
    return Optional.empty();
  }

  private Optional<MintedLink> insert(PortalLinkDal.NewLink link) {
    String token = PortalTokens.newToken();
    // Built before the insert so a misconfigured environment never writes a row it has no link for.
    Optional<String> url = PortalLinkPolicy.buildLink(token);
    if (!url.isPresent()) {
      return Optional.empty();
    }
    link.tokenHash = PortalTokens.hash(token);
    PortalLinkDal.insertPending(link);
    log.info("Minted pending portal link {} ({}) for business partner {} of client {}", link.id,
        link.origin, link.businessPartner.getId(), link.client.getId());
    return Optional.of(new MintedLink(link.id, url.get(), PortalLinkConfig.ttl()));
  }

  /**
   * Sends the renewal email through the one email service every contract uses.
   *
   * <p>As System — a portal request has no session — and inside {@link PortalRenewalScope}, the
   * marker the {@code portal-link-renewal} contract requires. The command names the tenant so the
   * service's throttles, audit and history row are the tenant's, and the document so the renewal
   * shows in the document's own email history.
   */
  private static String sendRenewalEmail(PortalLink expired) {
    JSONObject command = new JSONObject();
    try {
      command.put(FIELD_LINK_ID, expired.getId());
      command.put(EmailContractCommandSupport.FIELD_RECORD_ID, expired.getRecordId());
      command.put(EmailContractCommandSupport.FIELD_TENANT_ID, expired.getClientId());
      command.put(EmailContractCommandSupport.FIELD_LANGUAGE, expired.getLanguage());
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the portal renewal command", e);
    }
    return SystemContext.call("the portal link renewal",
        () -> PortalRenewalScope.call(() -> statusOf(
            new TransactionalEmailService().send(RENEWAL_CONTRACT, command))));
  }

  private static String statusOf(NeoResponse response) {
    if (response == null || response.getBody() == null) {
      return null;
    }
    JSONObject data = response.getBody().optJSONObject(STATUS_PATH_RESPONSE);
    data = data == null ? null : data.optJSONObject(STATUS_PATH_DATA);
    return data == null ? null : data.optString(STATUS_FIELD, null);
  }
}
