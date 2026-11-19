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
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.common.businesspartner.BusinessPartner;

import com.etendoerp.go.schemaforge.TenantOwnership;

/**
 * A customer's portal link handed to the user who asks for it, without an email (ETP-5850): the
 * contacts {@code portalLink} action, so the user (or an agent on their behalf) can paste it in a
 * message of their own. Same link as the one a document email carries, minted with origin
 * {@link PortalLink#ORIGIN_SHARED} and confirmed at once, like a decision link: it is valid for the
 * usual lifetime from now.
 *
 * <p>Conservative by design:</p>
 * <ul>
 *   <li><b>Same gate as the email link:</b> the {@code bp-portal-link} flag of the requesting
 *       account. Off, nothing is written.</li>
 *   <li><b>Same customer checks as opening a link:</b> a blocked or inactive customer, or a
 *       company whose plan expired, gets no link ({@link PortalAccessChecks}).</li>
 *   <li><b>An entry document is required:</b> every link opens on a document. The customer's most
 *       recent document the portal shows is used; a customer with none gets no link (its portal
 *       would be empty).</li>
 *   <li><b>A contact email is required:</b> a link that expires is renewed by email to its
 *       recipients, so the link records the contact's email as its recipient.</li>
 *   <li><b>Who asked is recorded:</b> the row's {@code CREATEDBY} is the requesting user.</li>
 *   <li><b>Not a send:</b> no "sent with link" KPI and no usage "sent" event; those count
 *       {@link PortalLink#ORIGIN_SEND} only.</li>
 * </ul>
 */
public class PortalContactShare {

  private static final Logger log = LogManager.getLogger(PortalContactShare.class);

  /** {@code CONTRACT_NAME} recorded on a shared link: no email contract sent it. */
  static final String SHARE_CONTRACT = "contact-portal-link";
  private static final String DEFAULT_LANGUAGE = "es_ES";
  /** {@code C_BPartner.EM_ETGO_EMAIL}, the email the Contacts form edits. */
  static final String PROPERTY_CONTACT_EMAIL = "etgoEmail";

  /** What a share request ended in. */
  public enum State {
    /** A new link was minted; its URL is in the outcome. */
    SHARED,
    /** The {@code bp-portal-link} flag is off for the requesting account. */
    DISABLED,
    /** No contact with that id in the session's tenant. */
    NOT_FOUND,
    /** The customer is blocked or inactive, or the company's plan expired. */
    UNAVAILABLE,
    /** The contact has no email to renew an expired link to. */
    NO_EMAIL,
    /** The customer has no document the portal shows. */
    NO_DOCUMENTS,
    /** The environment has no app base URL to build the link on. */
    NOT_CONFIGURED
  }

  /** The answer of a share request. */
  public static final class Outcome {
    private final State state;
    private final String url;
    private final Instant expiresAt;
    private final PortalDocumentKind documentKind;
    private final String documentId;
    private final String documentNo;
    private final String recipient;

    private Outcome(State state, String url, Instant expiresAt, PortalDocumentKind documentKind,
        String documentId, String documentNo, String recipient) {
      this.state = state;
      this.url = url;
      this.expiresAt = expiresAt;
      this.documentKind = documentKind;
      this.documentId = documentId;
      this.documentNo = documentNo;
      this.recipient = recipient;
    }

    static Outcome of(State state) {
      return new Outcome(state, null, null, null, null, null, null);
    }

    public State getState() {
      return state;
    }

    /** @return the link, only for {@link State#SHARED} */
    public String getUrl() {
      return url;
    }

    /** @return when the link stops working, only for {@link State#SHARED} */
    public Instant getExpiresAt() {
      return expiresAt;
    }

    /** @return the spec of the document the portal opens on, only for {@link State#SHARED} */
    public String getDocumentSpec() {
      return documentKind == null ? null : documentKind.getSpecName();
    }

    public String getDocumentId() {
      return documentId;
    }

    public String getDocumentNo() {
      return documentNo;
    }

    /** @return the contact email an expired link is renewed to */
    public String getRecipient() {
      return recipient;
    }
  }

  private final PortalAccessChecks accessChecks;
  private final Clock clock;

  /** The share on the portal's own access checks and the system clock. */
  public PortalContactShare() {
    this(new PortalAccessChecks(), Clock.systemUTC());
  }

  PortalContactShare(PortalAccessChecks accessChecks, Clock clock) {
    this.accessChecks = accessChecks;
    this.clock = clock;
  }

  /**
   * Mints and confirms a portal link for a customer, committed at once like every confirmed link.
   *
   * @param businessPartnerId the contact, in the session's tenant
   * @return the outcome; only {@link State#SHARED} wrote a row
   */
  public Outcome share(String businessPartnerId) {
    if (!PortalLinkPolicy.isLinkEnabled()) {
      return Outcome.of(State.DISABLED);
    }
    OBContext.setAdminMode(true);
    try {
      BusinessPartner customer = TenantOwnership.loadOwned(BusinessPartner.class,
          businessPartnerId);
      if (customer == null) {
        return Outcome.of(State.NOT_FOUND);
      }
      String clientId = customer.getClient().getId();
      if (accessChecks.blockReasonOrClosed(clientId, customer.getId()).isPresent()) {
        return Outcome.of(State.UNAVAILABLE);
      }
      String email = contactEmail(customer);
      if (email == null) {
        return Outcome.of(State.NO_EMAIL);
      }
      Optional<Object[]> entry = PortalDocumentQuery.latestVisible(clientId, customer.getId());
      if (!entry.isPresent()) {
        return Outcome.of(State.NO_DOCUMENTS);
      }
      String token = PortalTokens.newToken();
      Optional<String> url = PortalLinkPolicy.buildLink(token);
      if (!url.isPresent()) {
        return Outcome.of(State.NOT_CONFIGURED);
      }
      PortalDocumentKind kind = (PortalDocumentKind) entry.get()[0];
      BaseOBObject document = (BaseOBObject) entry.get()[1];
      Instant expiresAt = insertConfirmed(customer, kind, (String) document.getId(), email, token);
      log.info("Shared portal link for business partner {} of client {} on {} {}",
          customer.getId(), clientId, kind, document.getId());
      return new Outcome(State.SHARED, url.get(), expiresAt, kind, (String) document.getId(),
          (String) document.get("documentNo"), email);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Instant insertConfirmed(BusinessPartner customer, PortalDocumentKind kind,
      String documentId, String email, String token) {
    PortalLinkDal.NewLink link = new PortalLinkDal.NewLink();
    link.id = SequenceIdData.getUUID();
    link.client = customer.getClient();
    link.organization = customer.getOrganization();
    link.businessPartner = customer;
    link.tokenHash = PortalTokens.hash(token);
    link.kind = kind;
    link.recordId = documentId;
    link.origin = PortalLink.ORIGIN_SHARED;
    link.rootLinkId = link.id;
    link.contractName = SHARE_CONTRACT;
    link.recipientsTo = Collections.singletonList(email);
    link.recipientsCc = Collections.emptyList();
    link.language = customer.getLanguage() == null ? DEFAULT_LANGUAGE
        : StringUtils.defaultIfBlank(customer.getLanguage().getLanguage(), DEFAULT_LANGUAGE);
    PortalLinkDal.insertPending(link);
    Instant now = clock.instant();
    Instant expiresAt = now.plus(PortalLinkConfig.ttl());
    PortalLinkDal.confirm(PortalLinkDal.findById(link.id), Date.from(now), Date.from(expiresAt),
        null);
    SessionHandler.getInstance().commitAndStart();
    return expiresAt;
  }

  /**
   * The contact's email as the Contacts form edits it ({@code C_BPartner.EM_ETGO_EMAIL}), else the
   * oldest active contact person's ({@code AD_User}) — the address a document email defaults to.
   */
  private static String contactEmail(BusinessPartner customer) {
    Object own = customer.get(PROPERTY_CONTACT_EMAIL);
    if (own instanceof String && ((String) own).contains("@")) {
      return StringUtils.trimToNull((String) own);
    }
    List<String> emails = OBDal.getInstance().getSession()
        .createQuery("select u.email from ADUser u where u.businessPartner.id = :bp"
            + " and u.active = true and u.email like '%@%' order by u.creationDate", String.class)
        .setParameter("bp", customer.getId())
        .setMaxResults(1)
        .list();
    return emails.isEmpty() ? null : StringUtils.trimToNull(emails.get(0));
  }
}
