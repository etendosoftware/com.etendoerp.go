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
import java.util.List;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.PortalAccess;

/**
 * DAO boundary for {@code etgo_portal_access}.
 *
 * <p><b>Every query disables DAL's automatic client/organization filtering and states its scope
 * explicitly.</b> That is not a shortcut around security — it is required, and it is the safer of
 * the two options here. A portal request arrives with no session, so the context DAL would filter
 * against is the SYSTEM bootstrap ({@code AD_Client_ID = '0'}), which matches no tenant's row and
 * would make every lookup answer "not found" (the same wall
 * {@code PisReturnCallbackServlet#findPaymentBypassingFilters} documents). The scope is instead
 * supplied by the caller from the validated token row, which is the only place it may come from —
 * see {@link PortalSession}.
 */
final class PortalAccessDal {

  private static final String PARAM_TOKEN_HASH = "tokenHash";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_BPARTNER_ID = "bpartnerId";

  private PortalAccessDal() {
  }

  /**
   * Resolves the active access row a token hash belongs to.
   *
   * <p>"Active" means both {@code revoked_at is null} and {@code isactive = 'Y'} — the second so
   * that deactivating the row through ordinary AD tooling is as effective a kill switch as the
   * revoke action.
   *
   * @param tokenHash the hash of the presented token
   * @return the row, or {@code null} when no active row carries that hash
   */
  static PortalAccess findActiveByTokenHash(String tokenHash) {
    OBQuery<PortalAccess> query = OBDal.getInstance().createQuery(PortalAccess.class,
        "as p where p.tokenHash = :" + PARAM_TOKEN_HASH
            + " and p.revokedAt is null and p.active = true");
    query.setNamedParameter(PARAM_TOKEN_HASH, tokenHash);
    disableTenantFilters(query);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Resolves the single active row of a {@code (tenant, Business Partner)} pair.
   *
   * <p>This is the "look up the active row before inserting" half of the plan's application-level
   * uniqueness rule (§3): there is deliberately no DB unique constraint on the pair, because a
   * revoked row must be able to stay beside its replacement for audit.
   *
   * @param clientId the tenant
   * @param bpartnerId the Business Partner
   * @return the active row, or {@code null} when the pair has none
   */
  static PortalAccess findActiveForBusinessPartner(String clientId, String bpartnerId) {
    OBQuery<PortalAccess> query = OBDal.getInstance().createQuery(PortalAccess.class,
        "as p where p.client.id = :" + PARAM_CLIENT_ID
            + " and p.businessPartner.id = :" + PARAM_BPARTNER_ID
            + " and p.revokedAt is null and p.active = true"
            + " order by p.creationDate asc");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_BPARTNER_ID, bpartnerId);
    disableTenantFilters(query);
    query.setMaxResult(1);
    // Ordered + capped rather than uniqueResult(): the pair has no DB uniqueness, so two racing
    // first sends can leave two active rows. Answering with the oldest keeps the link a customer
    // may already have bookmarked working, instead of failing the read outright.
    List<PortalAccess> rows = query.list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Inserts a fully-formed access row for a {@code (tenant, Business Partner)} pair.
   *
   * <p>The id is supplied by the caller rather than left to Openbravo's own generator, because the
   * token is derived from it: the hash has to be known before the insert, and {@code token_hash} is
   * {@code NOT NULL}, so a two-step "save then fill in the hash" would fail on the first flush.
   * {@code SequenceIdData.getUUID()} is the module's existing way of minting an Etendo id up front
   * (see {@code OAuth2Servlet}).
   *
   * @param id the row id, already used to derive the token
   * @param client the tenant
   * @param organization the organization the row is visible at
   * @param businessPartner the Business Partner the token will speak for
   * @param tokenHash the hash of the token derived for this id
   * @return the saved row
   */
  static PortalAccess create(String id, Client client, Organization organization,
      BusinessPartner businessPartner, String tokenHash) {
    PortalAccess access = OBProvider.getInstance().get(PortalAccess.class);
    access.setNewOBObject(true);
    access.setId(id);
    access.setClient(client);
    access.setOrganization(organization);
    // setBpartner, not setBusinessPartner: generation derives the property name from the column
    // C_BPARTNER_ID, so PortalAccess exposes getBpartner/setBpartner. Sibling Openbravo entities
    // (Invoice, Shipment, ...) do use businessPartner — the name follows the column, not the type.
    access.setBpartner(businessPartner);
    access.setTokenHash(tokenHash);
    OBDal.getInstance().save(access);
    // Flushed, not committed: the row and the email that carries its link then succeed or fail
    // together. Committing here instead would leave a live token behind a send that rolled back.
    OBDal.getInstance().flush();
    return access;
  }

  /**
   * Stamps the moment a token was last successfully validated.
   *
   * @param access the row
   * @param when the validation instant
   */
  static void touchLastUsed(PortalAccess access, Date when) {
    access.setLastUsed(when);
    OBDal.getInstance().save(access);
    OBDal.getInstance().flush();
  }

  /**
   * Marks a row revoked. The row itself is kept — it is the audit trail of a link that was live.
   *
   * @param access the row
   * @param when the revocation instant
   */
  static void revoke(PortalAccess access, Date when) {
    access.setRevokedAt(when);
    OBDal.getInstance().save(access);
    OBDal.getInstance().flush();
  }

  private static void disableTenantFilters(OBQuery<?> query) {
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }
}
