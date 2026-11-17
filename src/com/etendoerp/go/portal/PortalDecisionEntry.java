package com.etendoerp.go.portal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

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

/**
 * The decision confirmation landing's answer, and its way into the portal (ETP-6209 Part C).
 *
 * <p>The landing is opened from the confirmation email, which carries a decision token, not a
 * portal token. After the confirmation (applied now, or answered before, superseded, no longer
 * pending, expired) the customer is taken into the portal on the quotation: a new portal link is
 * minted here, like a renewal (same tenant, Business Partner, recipients and language as the
 * decision's link, the 48-hour lifetime from now) but chained with origin {@code DECISION} and
 * usable at once, because no email carries it. Its raw token travels only in the confirmation's
 * POST answer ({@code portalToken}), never in a URL the server logs; only its hash is stored.</p>
 *
 * <p>No link when the token is invalid, when the portal's access checks closed it for the
 * customer, or when minting fails: the landing then keeps its own card. Re-opening the email link
 * mints again, so a decision link mints at most {@value #MAX_LINKS_PER_DAY} portal links per day
 * (counted by creation, like the renewal cool-down); the confirm route's rate limiter bounds the
 * request rate on top of that.</p>
 */
class PortalDecisionEntry {

  private static final Logger log = LogManager.getLogger(PortalDecisionEntry.class);

  /** At most this many portal links per decision link and day. */
  static final int MAX_LINKS_PER_DAY = 10;
  /** The answer's field with the new portal link's raw token. */
  static final String FIELD_PORTAL_TOKEN = "portalToken";
  /** The answer's field with the quotation the portal opens on. */
  static final String FIELD_QUOTATION_ID = "quotationId";

  private static final String FIELD_STATE = "state";
  private static final String FIELD_TENANT_NAME = "tenantName";
  private static final Duration ONE_DAY = Duration.ofDays(1);

  private final PortalLinkService linkService;
  private final Clock clock;

  /**
   * The landing over the portal's link service.
   *
   * @param linkService reads the decision's link
   */
  PortalDecisionEntry(PortalLinkService linkService) {
    this(linkService, Clock.systemUTC());
  }

  /**
   * As {@link #PortalDecisionEntry(PortalLinkService)}, with the clock that starts the lifetime.
   *
   * @param linkService reads the decision's link
   * @param clock the time source
   */
  PortalDecisionEntry(PortalLinkService linkService, Clock clock) {
    this.linkService = linkService;
    this.clock = clock;
  }

  /**
   * The landing's answer for a decision: its state, what was decided and for which quotation.
   *
   * @param view the decision's view, not {@code INVALID}
   * @return the answer's body
   * @throws JSONException never in practice
   */
  JSONObject body(PortalDecisionService.View view) throws JSONException {
    PortalDecisionService.Decision decision = view.getDecision();
    JSONObject body = new JSONObject();
    body.put(FIELD_STATE, view.getState().getCode());
    body.put("appliedNow", view.isAppliedNow());
    body.put("decision", decision.getDecision());
    if (decision.getReasonCode() != null) {
      body.put("reasonCode", decision.getReasonCode());
    }
    if (decision.getReasonText() != null) {
      body.put("reasonText", decision.getReasonText());
    }
    body.put("documentNo", view.getQuotation() == null ? ""
        : StringUtils.defaultString(view.getQuotation().getDocumentNo()));
    // The company's name only: the landing carries no portal token, so it cannot fetch /logo.
    body.put(FIELD_TENANT_NAME, linkService.findLink(decision.getLinkId())
        .map(PortalLink::getClientName).orElse(""));
    return body;
  }

  /**
   * Adds the way into the portal to a confirmation's answer: the new link's raw token and the
   * quotation, when the customer may enter and the link could be minted. Nothing is added
   * otherwise, and the landing keeps its own card.
   *
   * @param body the confirmation's answer, from {@link #body}
   * @param view the confirmation's view
   * @throws JSONException never in practice
   */
  void addPortalEntry(JSONObject body, PortalDecisionService.View view) throws JSONException {
    if (!entersPortal(view)) {
      return;
    }
    PortalDecisionService.Decision decision = view.getDecision();
    String token = mint(decision.getLinkId(), decision.getQuotationId());
    if (token != null) {
      body.put(FIELD_PORTAL_TOKEN, token);
      body.put(FIELD_QUOTATION_ID, decision.getQuotationId());
    }
  }

  /**
   * Whether a confirmation's answer leads into the portal: a known decision of a customer the
   * portal is open to. Every state but {@code INVALID} does (applied, answered before, superseded,
   * no longer pending, expired); the customer is told which by the portal's notice.
   *
   * @param view the confirmation's view
   * @return {@code true} when a portal link may be minted
   */
  static boolean entersPortal(PortalDecisionService.View view) {
    return view != null && view.getDecision() != null && !view.isCustomerClosed()
        && view.getState() != PortalDecisionService.State.INVALID;
  }

  /**
   * Mints the portal link: pending and confirmed at once, committed here. Never throws.
   *
   * @param decisionLinkId the link the decision was requested from
   * @param quotationId the quotation the portal opens on
   * @return the raw token, or {@code null} when nothing was minted
   */
  String mint(String decisionLinkId, String quotationId) {
    OBContext.setAdminMode(true);
    try {
      Optional<PortalLink> found = linkService.findLink(decisionLinkId);
      if (!found.isPresent() || StringUtils.isBlank(quotationId) || overDailyLimit(decisionLinkId)) {
        return null;
      }
      return insertConfirmed(found.get(), quotationId);
    } catch (RuntimeException e) {
      log.error("Could not mint the portal link of decision link {}", decisionLinkId, e);
      discardFailedWrite();
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean overDailyLimit(String decisionLinkId) {
    Date since = Date.from(clock.instant().minus(ONE_DAY));
    boolean over = PortalLinkDal.countChildrenOf(decisionLinkId, PortalLink.ORIGIN_DECISION,
        since) >= MAX_LINKS_PER_DAY;
    if (over) {
      log.info("Portal link of decision link {} not minted: daily limit reached", decisionLinkId);
    }
    return over;
  }

  private String insertConfirmed(PortalLink source, String quotationId) {
    String token = PortalTokens.newToken();
    PortalLinkDal.NewLink link = new PortalLinkDal.NewLink();
    link.id = SequenceIdData.getUUID();
    link.client = OBDal.getInstance().get(Client.class, source.getClientId());
    link.organization = OBDal.getInstance().get(Organization.class, source.getOrganizationId());
    link.businessPartner = OBDal.getInstance()
        .get(BusinessPartner.class, source.getBusinessPartnerId());
    link.tokenHash = PortalTokens.hash(token);
    link.kind = PortalDocumentKind.QUOTATION;
    link.recordId = quotationId;
    link.origin = PortalLink.ORIGIN_DECISION;
    link.parentLinkId = source.getId();
    link.rootLinkId = StringUtils.defaultIfBlank(source.getRootLinkId(), source.getId());
    link.contractName = PortalDecisionService.CONFIRM_CONTRACT;
    link.recipientsTo = source.getRecipientsTo();
    link.recipientsCc = source.getRecipientsCc();
    link.language = source.getLanguage();
    PortalLinkDal.insertPending(link);
    BaseOBObject row = PortalLinkDal.findById(link.id);
    Instant now = clock.instant();
    PortalLinkDal.confirm(row, Date.from(now), Date.from(now.plus(PortalLinkConfig.ttl())), null);
    SessionHandler.getInstance().commitAndStart();
    log.info("Minted decision portal link {} from link {} for business partner {} of client {}",
        link.id, source.getId(), source.getBusinessPartnerId(), source.getClientId());
    return token;
  }

  private static void discardFailedWrite() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.error("Could not roll back the failed decision portal link", e);
    }
  }
}
