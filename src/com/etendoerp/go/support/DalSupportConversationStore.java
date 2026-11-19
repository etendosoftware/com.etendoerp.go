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

package com.etendoerp.go.support;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * DAL-backed {@link SupportConversationStore}: {@code ETGO_Support_Conversation} and
 * {@code ETGO_Support_Message} through {@link OBDal}, new rows through {@link OBProvider}, and the
 * request context through {@link OBContext}.
 */
class DalSupportConversationStore implements SupportConversationStore {

  /** Switches the ambient {@code OBContext} to the request's user/role/client/org so {@code OBDal}
   * criteria queries see this tenant's rows, then enters admin mode on top to bypass entity-level
   * access checks. The real role (not System Administrator) matters here: {@code OBContext}
   * computes readable clients from {@code role.getUserLevel()} — a System-level role (like role
   * "0") always resolves readable clients to {@code ["0"]} regardless of the clientId passed in,
   * silently hiding every other client's rows even with {@code setAdminMode(true)} active. */
  @Override
  public OBContext enterTenantAdminMode(String userId, String roleId, String clientId, String orgId) {
    OBContext previous = OBContext.getOBContext();
    OBContext.setOBContext(userId, roleId, clientId, orgId);
    OBContext.setAdminMode(true);
    return previous;
  }

  @Override
  public void restoreTenantContext(OBContext previous) {
    OBContext.restorePreviousMode();
    OBContext.setOBContext(previous);
  }

  @Override
  public void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  @Override
  public void restorePreviousMode() {
    OBContext.restorePreviousMode();
  }

  @Override
  public User findUser(String userId) {
    return OBDal.getInstance().get(User.class, userId);
  }

  @Override
  public Client findClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  @Override
  public Organization findOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  @Override
  public SupportConversation findConversation(String conversationId) {
    return OBDal.getInstance().get(SupportConversation.class, conversationId);
  }

  @Override
  public List<SupportConversation> listConversationsOfUser(User user) {
    OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
    crit.add(Restrictions.eq(SupportConversation.PROPERTY_USER, user));
    crit.addOrderBy(SupportConversation.PROPERTY_LASTACTIVITY, false);
    return crit.list();
  }

  /** System/webhook calls have no per-request tenant to scope to — must see every client's
   * conversations to resolve the Jira ticket key. */
  @Override
  public List<SupportConversation> listConversationsByJiraKey(String jiraKey) {
    OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportConversation.PROPERTY_JIRATICKETKEY, jiraKey));
    return crit.list();
  }

  /** Jira webhooks arrive with no per-request tenant to scope to — this lookup must see every
   * client's rows to resolve the ticket key. */
  @Override
  public SupportConversation findConversationByJiraKey(String jiraKey) {
    OBCriteria<SupportConversation> crit = OBDal.getInstance().createCriteria(SupportConversation.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportConversation.PROPERTY_JIRATICKETKEY, jiraKey));
    crit.setMaxResults(1);
    return (SupportConversation) crit.uniqueResult();
  }

  @Override
  public List<SupportMessage> listMessages(String conversationId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.add(Restrictions.eq(SupportMessage.PROPERTY_CONVERSATION + ".id", conversationId));
    crit.addOrderBy(SupportMessage.PROPERTY_MESSAGEDATE, true);
    return crit.list();
  }

  @Override
  public List<SupportMessage> listMessagesWithAttachments(String conversationId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportMessage.PROPERTY_CONVERSATION + ".id", conversationId));
    crit.add(Restrictions.isNotNull(SupportMessage.PROPERTY_ATTACHMENTS));
    return crit.list();
  }

  /** The {@code LIKE} below is only a cheap pre-filter, not the authorization decision. */
  @Override
  public List<SupportMessage> listMessagesMentioningAttachment(String jiraAttachmentId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.isNotNull(SupportMessage.PROPERTY_ATTACHMENTS));
    crit.add(Restrictions.like(SupportMessage.PROPERTY_ATTACHMENTS, "%\"" + jiraAttachmentId + "\"%"));
    return crit.list();
  }

  @Override
  public SupportMessage findMessageByExternalId(String externalId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(SupportMessage.PROPERTY_EXTERNALID, externalId));
    crit.setMaxResults(1);
    return (SupportMessage) crit.uniqueResult();
  }

  @Override
  public SupportConversation newConversation() {
    return OBProvider.getInstance().get(SupportConversation.class);
  }

  @Override
  public SupportMessage newMessage() {
    return OBProvider.getInstance().get(SupportMessage.class);
  }

  @Override
  public void saveConversation(SupportConversation conversation) {
    OBDal.getInstance().save(conversation);
  }

  @Override
  public void saveMessage(SupportMessage message) {
    OBDal.getInstance().save(message);
  }

  @Override
  public void flush() {
    OBDal.getInstance().flush();
  }

  /** Uses {@link SessionHandler} directly (not a raw JDBC commit) so the Hibernate Transaction
   * stays in sync and the rows saved afterwards are actually committed at end of request. */
  @Override
  public void commitAndStart() {
    SessionHandler.getInstance().commitAndStart();
  }
}
