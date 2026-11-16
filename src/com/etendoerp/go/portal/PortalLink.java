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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * An immutable read of one {@code etgo_portal_link} row: one emailed link (ETP-6209).
 *
 * <p>Built only by {@link PortalLinkDal}, so the rest of the portal never touches the DAL object and
 * cannot write a field by accident.
 */
public final class PortalLink {

  /** {@code LINK_ORIGIN} of a link that went out with a document send. */
  public static final String ORIGIN_SEND = "SEND";
  /** {@code LINK_ORIGIN} of a link emailed because an older one had expired. */
  public static final String ORIGIN_RENEWAL = "RENEWAL";

  private static final String RECIPIENT_SEPARATOR = ",";

  private final String id;
  private final String clientId;
  private final String clientName;
  private final String organizationId;
  private final String businessPartnerId;
  private final String businessPartnerName;
  private final PortalDocumentKind documentKind;
  private final String recordId;
  private final String origin;
  private final String parentLinkId;
  private final String rootLinkId;
  private final String contractName;
  private final List<String> recipientsTo;
  private final List<String> recipientsCc;
  private final String language;
  private final Instant sentAt;
  private final Instant expiresAt;
  private final boolean active;
  private final boolean used;

  // A private value carrier built field by field by the DAL boundary; a builder would add a second
  // type with the same field list and no extra safety.
  @SuppressWarnings("java:S107")
  PortalLink(String id, String clientId, String clientName, String organizationId,
      String businessPartnerId, String businessPartnerName, PortalDocumentKind documentKind,
      String recordId, String origin, String parentLinkId, String rootLinkId,
      String contractName, String recipientsTo, String recipientsCc, String language,
      Date sentAt, Date expiresAt, boolean active, boolean used) {
    this.id = id;
    this.clientId = clientId;
    this.clientName = clientName;
    this.organizationId = organizationId;
    this.businessPartnerId = businessPartnerId;
    this.businessPartnerName = businessPartnerName;
    this.documentKind = documentKind;
    this.recordId = recordId;
    this.origin = origin;
    this.parentLinkId = parentLinkId;
    this.rootLinkId = rootLinkId;
    this.contractName = contractName;
    this.recipientsTo = split(recipientsTo);
    this.recipientsCc = split(recipientsCc);
    this.language = language;
    this.sentAt = sentAt == null ? null : sentAt.toInstant();
    this.expiresAt = expiresAt == null ? null : expiresAt.toInstant();
    this.active = active;
    this.used = used;
  }

  /**
   * Whether the link's email actually left. A pending link (minted for an email that was then
   * throttled, refused as duplicate or failed at the provider) is never usable.
   *
   * @return {@code true} once the send was confirmed
   */
  public boolean isSent() {
    return sentAt != null;
  }

  /**
   * Whether the link's lifetime is over at the given instant (an unsent link never expires).
   *
   * @param now the reference instant
   * @return {@code true} when the link was sent and its lifetime is over
   */
  public boolean isExpiredAt(Instant now) {
    return isSent() && expiresAt != null && !now.isBefore(expiresAt);
  }

  public String getId() {
    return id;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientName() {
    return clientName;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getBusinessPartnerId() {
    return businessPartnerId;
  }

  public String getBusinessPartnerName() {
    return businessPartnerName;
  }

  public PortalDocumentKind getDocumentKind() {
    return documentKind;
  }

  public String getRecordId() {
    return recordId;
  }

  public String getOrigin() {
    return origin;
  }

  public String getParentLinkId() {
    return parentLinkId;
  }

  /**
   * @return the {@code SEND} link this one descends from — its own id for a {@code SEND} link. The
   *     KPI unit "one send".
   */
  public String getRootLinkId() {
    return rootLinkId;
  }

  public String getContractName() {
    return contractName;
  }

  public List<String> getRecipientsTo() {
    return recipientsTo;
  }

  public List<String> getRecipientsCc() {
    return recipientsCc;
  }

  public String getLanguage() {
    return language;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public boolean isActive() {
    return active;
  }

  /**
   * @return {@code true} when the link had already been opened at least once
   */
  public boolean isUsed() {
    return used;
  }

  private static List<String> split(String joined) {
    if (StringUtils.isBlank(joined)) {
      return Collections.emptyList();
    }
    List<String> addresses = new ArrayList<>();
    for (String part : joined.split(RECIPIENT_SEPARATOR)) {
      String address = StringUtils.trimToNull(part);
      if (address != null) {
        addresses.add(address);
      }
    }
    return Collections.unmodifiableList(addresses);
  }
}
