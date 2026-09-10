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

import java.util.Date;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.PortalAccess;

/**
 * Owns the lifecycle of a Business Partner's portal access: minting the link that goes into an
 * invoice email, validating the token that comes back, and revoking it.
 *
 * <p><b>Both gates of {@link PortalLinkPolicy} are checked inside {@link #findOrCreateLink}, before
 * anything is written.</b> Deliberately here rather than at the call site: minting a row while a
 * gate is closed is the failure mode that leaks the feature early — a live token exists for a
 * Business Partner nobody meant to give one — and it is what the plan's tests assert on (the row
 * count, not the email body). Putting the check in the only method that inserts makes it
 * unreachable by any caller.
 *
 * <p><b>Validation resolves the scope, never the caller.</b> {@link #validate} answers a
 * {@link PortalSession} built entirely from the row it found. Unknown and revoked tokens are
 * indistinguishable to the caller: both answer {@link Optional#empty()}, and the servlet turns both
 * into the same generic response — the anti-enumeration rule the password-reset flow already
 * follows.
 */
public class PortalAccessService {

  private static final Logger log = LogManager.getLogger(PortalAccessService.class);

  /**
   * Finds the Business Partner's active portal link, creating the access row on first use.
   *
   * <p>Stable across sends: the second invoice to the same Business Partner resolves the same row
   * and therefore the same link, which is what makes the link bookmarkable. It never rotates on its
   * own — only {@link #revoke} followed by a later send does that, and that produces a new row.
   *
   * @param client the tenant that owns the invoice being sent
   * @param organization the organization the access row is visible at
   * @param businessPartner the Business Partner the invoice is addressed to
   * @return the absolute portal link, or empty when either gate is closed, the token secret or app
   *     base URL is unconfigured, or an argument is missing
   */
  public Optional<String> findOrCreateLink(Client client, Organization organization,
      BusinessPartner businessPartner) {
    if (client == null || organization == null || businessPartner == null) {
      return Optional.empty();
    }
    // Gate order is PortalLinkPolicy's contract: flag (in-memory) before preference (a query).
    if (!PortalLinkPolicy.isLinkEnabled()) {
      return Optional.empty();
    }
    // Checked before the lookup so a misconfigured environment never inserts a row it cannot build
    // a link for.
    if (!PortalTokens.isSecretConfigured()) {
      log.warn("A portal link was requested but {} is not configured; no link will be sent",
          PortalTokens.PROP_TOKEN_SECRET);
      return Optional.empty();
    }
    try {
      PortalAccess access = PortalAccessDal.findActiveForBusinessPartner(client.getId(),
          businessPartner.getId());
      String accessId = access != null ? access.getId()
          : mint(client, organization, businessPartner);
      if (accessId == null) {
        return Optional.empty();
      }
      return PortalTokens.deriveToken(accessId).flatMap(PortalLinkPolicy::buildLink);
    } catch (RuntimeException e) {
      // An invoice must still be emailed when the portal link cannot be produced. The link is an
      // addition to that email, never a precondition for it.
      log.error("Could not resolve the portal link for business partner {} of client {}",
          businessPartner.getId(), client.getId(), e);
      return Optional.empty();
    }
  }

  /**
   * Validates a presented token and stamps the row as used.
   *
   * @param rawToken the token from the URL path or the {@code Authorization} header
   * @return the scope the token speaks for, or empty when the token is unknown, revoked or blank —
   *     three cases the caller must not be able to tell apart
   */
  public Optional<PortalSession> validate(String rawToken) {
    String tokenHash = PortalTokens.hash(rawToken);
    if (tokenHash == null) {
      return Optional.empty();
    }
    try {
      PortalAccess access = PortalAccessDal.findActiveByTokenHash(tokenHash);
      if (access == null || access.getClient() == null || access.getBusinessPartner() == null) {
        return Optional.empty();
      }
      PortalAccessDal.touchLastUsed(access, new Date());
      return Optional.of(new PortalSession(access.getId(), access.getClient().getId(),
          access.getBusinessPartner().getId(), access.getBusinessPartner().getName(),
          access.getClient().getName()));
    } catch (RuntimeException e) {
      // Never says why. A validation that fails for an infrastructure reason must look exactly like
      // an unknown token to whoever is holding it.
      log.error("Portal token validation failed", e);
      return Optional.empty();
    }
  }

  /**
   * Revokes the Business Partner's active link. The MVP's only kill switch for a leaked link.
   *
   * <p>Idempotent: a pair with no active row is already revoked, and answers {@code false} without
   * writing. The revoked row itself is kept — it is the record that a link was once live, and the
   * reason the {@code (client, business partner)} pair carries no DB unique constraint.
   *
   * @param clientId the tenant, resolved server-side from the caller's own session
   * @param bpartnerId the Business Partner whose link is being revoked
   * @return {@code true} when a row was revoked by this call
   */
  public boolean revoke(String clientId, String bpartnerId) {
    if (StringUtils.isAnyBlank(clientId, bpartnerId)) {
      return false;
    }
    PortalAccess access = PortalAccessDal.findActiveForBusinessPartner(clientId, bpartnerId);
    if (access == null) {
      return false;
    }
    PortalAccessDal.revoke(access, new Date());
    log.info("Revoked portal access {} for business partner {} of client {}", access.getId(),
        bpartnerId, clientId);
    return true;
  }

  /**
   * Reports whether a Business Partner currently has a live link, without disclosing it.
   *
   * <p>The token is deliberately not returned: it is derived on the send path and belongs in the
   * customer's email, not in an internal-user API response that could be logged or screenshotted.
   *
   * @param clientId the tenant, resolved server-side from the caller's own session
   * @param bpartnerId the Business Partner to inspect
   * @return {@code true} when an active, unrevoked access row exists
   */
  public boolean hasActiveAccess(String clientId, String bpartnerId) {
    if (StringUtils.isAnyBlank(clientId, bpartnerId)) {
      return false;
    }
    return PortalAccessDal.findActiveForBusinessPartner(clientId, bpartnerId) != null;
  }

  /**
   * Inserts one access row, with its id minted first so the token can be derived from it before the
   * insert (see {@link PortalAccessDal#create}).
   *
   * @return the new row's id, or {@code null} when the token could not be derived — in which case
   *     nothing was written
   */
  private String mint(Client client, Organization organization, BusinessPartner businessPartner) {
    String accessId = SequenceIdData.getUUID();
    Optional<String> token = PortalTokens.deriveToken(accessId);
    if (!token.isPresent()) {
      return null;
    }
    PortalAccessDal.create(accessId, client, organization, businessPartner,
        PortalTokens.hash(token.get()));
    log.info("Minted portal access {} for business partner {} of client {}", accessId,
        businessPartner.getId(), client.getId());
    return accessId;
  }
}
