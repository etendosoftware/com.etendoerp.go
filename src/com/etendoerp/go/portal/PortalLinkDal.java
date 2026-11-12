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

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.DynamicOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;

/**
 * DAO boundary for {@code etgo_portal_link} (ETP-6209).
 *
 * <p><b>Written against the entity name, not a generated class</b>, so this code compiles before
 * {@code generate.entities} has produced {@code com.etendoerp.go.schemaforge.data.PortalLink} (whose
 * simple name would also collide with {@link PortalLink}). The property names below are what the
 * DAL derives from each {@code AD_Column.Name} ({@code NamingUtil#getPropertyMappingName}: strip
 * {@code _ID}, strip a one- or two-letter prefix, camel-case on {@code _}).
 *
 * <p><b>A new row is instantiated through the entity's mapping class, never as a
 * {@link DynamicOBObject}.</b> The table has a generated class, so Hibernate maps the entity to
 * it. A row saved as a {@code DynamicOBObject} is cached in the session under that entity, and the
 * next query that loads it in the same session — {@link #confirm}'s {@link #findById} right after
 * the email leaves — fails with {@code WrongClassException} ("loaded object was of wrong class
 * DynamicOBObject"). That exception poisons the whole transaction (ETP-6209 live test: the link,
 * the send history and the anti-abuse audit were all rolled back while the customer already had
 * the email). {@link #newRow()} resolves the class from the runtime model instead.
 *
 * <p><b>Every query disables DAL's automatic client/organization filtering and states its scope
 * explicitly.</b> A portal request arrives with no session, so the context DAL would filter against
 * is the SYSTEM bootstrap, which matches no tenant's row (see {@code PisReturnCallbackServlet}).
 * The scope is supplied by the caller from the validated link, the only place it may come from.
 */
final class PortalLinkDal {

  static final String ENTITY = "ETGO_Portal_Link";

  static final String PROP_ID = "id";
  static final String PROP_CLIENT = "client";
  static final String PROP_ORGANIZATION = "organization";
  static final String PROP_ACTIVE = "active";
  static final String PROP_CREATION_DATE = "creationDate";
  static final String PROP_BPARTNER = "bpartner";
  static final String PROP_TOKEN_HASH = "tokenHash";
  static final String PROP_TABLE = "table";
  static final String PROP_RECORD = "record";
  static final String PROP_DOCUMENT_KIND = "documentKind";
  static final String PROP_LINK_ORIGIN = "linkOrigin";
  static final String PROP_PARENT_LINK = "parentLink";
  static final String PROP_ROOT_LINK = "rootLink";
  static final String PROP_CONTRACT_NAME = "contractName";
  static final String PROP_EMAIL_SEND_LOG = "emailSendLog";
  static final String PROP_RECIPIENTS_TO = "recipientsTo";
  static final String PROP_RECIPIENTS_CC = "recipientsCC";
  static final String PROP_EMAIL_LANGUAGE = "emailLanguage";
  static final String PROP_SENT_AT = "sentAt";
  static final String PROP_EXPIRES_AT = "expiresAt";
  static final String PROP_FIRST_USED = "firstUsed";
  static final String PROP_LAST_USED = "lastUsed";

  private static final int LEN_RECIPIENTS = 2000;
  private static final int LEN_CONTRACT_NAME = 80;
  private static final int LEN_LANGUAGE = 10;
  private static final String RECIPIENT_JOIN = ", ";

  private PortalLinkDal() {
  }

  /** Everything a new row needs; {@code id} is minted by the caller. */
  static final class NewLink {
    String id;
    Client client;
    Organization organization;
    BusinessPartner businessPartner;
    String tokenHash;
    PortalDocumentKind kind;
    String recordId;
    String origin;
    String parentLinkId;
    String rootLinkId;
    String contractName;
    List<String> recipientsTo;
    List<String> recipientsCc;
    String language;
  }

  /**
   * Inserts a pending link: {@code SENT_AT} stays null until the email is confirmed delivered.
   *
   * <p>Flushed, not committed: the row and the email that carries its link succeed or fail
   * together with the request's transaction.
   *
   * @param link the values of the new row
   */
  static void insertPending(NewLink link) {
    BaseOBObject row = newRow();
    row.setId(link.id);
    row.setNewOBObject(true);
    row.set(PROP_CLIENT, link.client);
    row.set(PROP_ORGANIZATION, link.organization);
    row.set(PROP_ACTIVE, Boolean.TRUE);
    row.set(PROP_BPARTNER, link.businessPartner);
    row.set(PROP_TOKEN_HASH, link.tokenHash);
    row.set(PROP_TABLE, findTable(link.kind.getTableName()));
    row.set(PROP_RECORD, link.recordId);
    row.set(PROP_DOCUMENT_KIND, link.kind.name());
    row.set(PROP_LINK_ORIGIN, link.origin);
    row.set(PROP_PARENT_LINK, link.parentLinkId);
    row.set(PROP_ROOT_LINK, link.rootLinkId);
    row.set(PROP_CONTRACT_NAME, truncate(link.contractName, LEN_CONTRACT_NAME));
    row.set(PROP_RECIPIENTS_TO, truncate(join(link.recipientsTo), LEN_RECIPIENTS));
    row.set(PROP_RECIPIENTS_CC, truncate(join(link.recipientsCc), LEN_RECIPIENTS));
    row.set(PROP_EMAIL_LANGUAGE, truncate(link.language, LEN_LANGUAGE));
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
  }

  /**
   * Resolves the row a token hash belongs to, active or not, sent or not. The caller decides what
   * each state means.
   *
   * @param tokenHash the hash of the presented token
   * @return the row, or {@code null}
   */
  static BaseOBObject findByTokenHash(String tokenHash) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY,
        "as l where l." + PROP_TOKEN_HASH + " = :tokenHash");
    query.setNamedParameter("tokenHash", tokenHash);
    return first(query);
  }

  /**
   * @param linkId the row id
   * @return the row, active or not, or {@code null}
   */
  static BaseOBObject findById(String linkId) {
    if (StringUtils.isBlank(linkId)) {
      return null;
    }
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY,
        "as l where l." + PROP_ID + " = :linkId");
    query.setNamedParameter("linkId", linkId);
    return first(query);
  }

  /**
   * Marks a pending link as sent and starts its lifetime.
   *
   * @param row the pending row
   * @param sentAt when the email left
   * @param expiresAt when the link stops being valid
   * @param sendLogId the {@code etgo_email_send_log} row of that send, may be {@code null}
   */
  static void confirm(BaseOBObject row, Date sentAt, Date expiresAt, String sendLogId) {
    row.set(PROP_SENT_AT, sentAt);
    row.set(PROP_EXPIRES_AT, expiresAt);
    row.set(PROP_EMAIL_SEND_LOG, StringUtils.trimToNull(sendLogId));
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
  }

  /**
   * Stamps a successful validation.
   *
   * @param row the link
   * @param when the validation instant
   */
  static void touchUsed(BaseOBObject row, Date when) {
    if (row.get(PROP_FIRST_USED) == null) {
      row.set(PROP_FIRST_USED, when);
    }
    row.set(PROP_LAST_USED, when);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
  }

  /**
   * Counts the confirmed renewals an expired link already produced since an instant — the per-link
   * cool-down.
   */
  static int countSentRenewalsOf(String parentLinkId, Date since) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY,
        "as l where l." + PROP_PARENT_LINK + " = :parentLinkId"
            + " and l." + PROP_LINK_ORIGIN + " = :origin"
            + " and l." + PROP_SENT_AT + " >= :since");
    query.setNamedParameter("parentLinkId", parentLinkId);
    query.setNamedParameter("origin", PortalLink.ORIGIN_RENEWAL);
    query.setNamedParameter("since", since);
    disableTenantFilters(query);
    return query.count();
  }

  /**
   * Counts the confirmed renewals sent for a Business Partner of a tenant since an instant — the
   * daily ceiling.
   */
  static int countSentRenewalsForBusinessPartner(String clientId, String bpartnerId, Date since) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY,
        "as l where l." + PROP_CLIENT + ".id = :clientId"
            + " and l." + PROP_BPARTNER + ".id = :bpartnerId"
            + " and l." + PROP_LINK_ORIGIN + " = :origin"
            + " and l." + PROP_SENT_AT + " >= :since");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("bpartnerId", bpartnerId);
    query.setNamedParameter("origin", PortalLink.ORIGIN_RENEWAL);
    query.setNamedParameter("since", since);
    disableTenantFilters(query);
    return query.count();
  }

  /**
   * Reads a row into its immutable view.
   *
   * @param row the DAL row
   * @return the view
   */
  static PortalLink toLink(BaseOBObject row) {
    BaseOBObject client = (BaseOBObject) row.get(PROP_CLIENT);
    BaseOBObject organization = (BaseOBObject) row.get(PROP_ORGANIZATION);
    BaseOBObject bpartner = (BaseOBObject) row.get(PROP_BPARTNER);
    return new PortalLink((String) row.getId(), idOf(client),
        client instanceof Client ? ((Client) client).getName() : null, idOf(organization),
        idOf(bpartner),
        bpartner instanceof BusinessPartner ? ((BusinessPartner) bpartner).getName() : null,
        PortalDocumentKind.fromCode((String) row.get(PROP_DOCUMENT_KIND)).orElse(null),
        (String) row.get(PROP_RECORD), (String) row.get(PROP_LINK_ORIGIN),
        (String) row.get(PROP_PARENT_LINK), (String) row.get(PROP_ROOT_LINK),
        (String) row.get(PROP_CONTRACT_NAME), (String) row.get(PROP_RECIPIENTS_TO),
        (String) row.get(PROP_RECIPIENTS_CC), (String) row.get(PROP_EMAIL_LANGUAGE),
        (Date) row.get(PROP_SENT_AT), (Date) row.get(PROP_EXPIRES_AT),
        Boolean.TRUE.equals(row.get(PROP_ACTIVE)), row.get(PROP_FIRST_USED) != null);
  }

  /**
   * A new, empty row of the entity, of the class the session maps it to: the generated class once
   * {@code generate.entities} has run, a {@link DynamicOBObject} only for an entity the runtime
   * model maps to no class.
   */
  private static BaseOBObject newRow() {
    Class<?> mappingClass = ModelProvider.getInstance().getEntity(ENTITY).getMappingClass();
    if (mappingClass == null || DynamicOBObject.class.equals(mappingClass)) {
      DynamicOBObject row = new DynamicOBObject();
      row.setEntityName(ENTITY);
      return row;
    }
    return (BaseOBObject) OBProvider.getInstance().get(mappingClass);
  }

  private static BaseOBObject first(OBQuery<BaseOBObject> query) {
    disableTenantFilters(query);
    // Inactive rows are read too: an operator deactivating a row must make it answer "invalid",
    // which the caller decides, not vanish into "unknown" — both answers are identical anyway.
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    List<BaseOBObject> rows = query.list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Table findTable(String tableName) {
    OBQuery<Table> query = OBDal.getInstance().createQuery(Table.class,
        "as t where lower(t." + Table.PROPERTY_DBTABLENAME + ") = lower(:tableName)");
    query.setNamedParameter("tableName", tableName);
    disableTenantFilters(query);
    query.setMaxResult(1);
    List<Table> tables = query.list();
    return tables.isEmpty() ? null : tables.get(0);
  }

  private static String idOf(BaseOBObject object) {
    return object == null ? null : (String) object.getId();
  }

  private static String join(List<String> addresses) {
    return addresses == null || addresses.isEmpty() ? null
        : StringUtils.join(addresses, RECIPIENT_JOIN);
  }

  private static String truncate(String value, int maxLength) {
    String normalized = StringUtils.trimToNull(value);
    return normalized == null || normalized.length() <= maxLength ? normalized
        : normalized.substring(0, maxLength);
  }

  private static void disableTenantFilters(OBQuery<?> query) {
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }
}
