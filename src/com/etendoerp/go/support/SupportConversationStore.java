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

import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * Persistence of the support chat: the {@link SupportConversation} rows, their
 * {@link SupportMessage} rows, the few reference rows a new conversation is stamped with, and the
 * request context the support endpoints run their reads and writes in.
 *
 * <p>The support servlet, the inbound Jira webhook and their helpers reach the database only
 * through this interface, so their own logic can be tested with a stub store and no DAL.
 * {@link DalSupportConversationStore} is the real one.</p>
 *
 * <p>Lookups by Jira key, external id or attachment id run across every client: they serve
 * webhooks and system calls that carry no tenant, or a tenant other than the one the row was
 * stamped with. Lookups by owner or conversation id honour the ambient context.</p>
 */
interface SupportConversationStore {

  /**
   * Switches the ambient context to the request's user, role, client and organization and enters
   * admin mode on top of it.
   *
   * @param userId   the requesting user
   * @param roleId   the requesting role
   * @param clientId the request's client
   * @param orgId    the request's organization
   * @return the context to hand back to {@link #restoreTenantContext(OBContext)}
   */
  OBContext enterTenantAdminMode(String userId, String roleId, String clientId, String orgId);

  /**
   * Leaves the admin mode entered by {@link #enterTenantAdminMode} and puts back {@code previous}.
   *
   * @param previous the context {@link #enterTenantAdminMode} returned
   */
  void restoreTenantContext(OBContext previous);

  /** Enters admin mode on the current context. Pair with {@link #restorePreviousMode()}. */
  void enterAdminMode();

  /** Leaves the admin mode entered by {@link #enterAdminMode()}. */
  void restorePreviousMode();

  /**
   * Looks up a user.
   *
   * @param userId the user id
   * @return the user, or {@code null}
   */
  User findUser(String userId);

  /**
   * Looks up a client.
   *
   * @param clientId the client id
   * @return the client, or {@code null}
   */
  Client findClient(String clientId);

  /**
   * Looks up an organization.
   *
   * @param orgId the organization id
   * @return the organization, or {@code null}
   */
  Organization findOrganization(String orgId);

  /**
   * Looks up a conversation.
   *
   * @param conversationId the conversation id
   * @return the conversation with this id, or {@code null}
   */
  SupportConversation findConversation(String conversationId);

  /**
   * Lists the conversations a user owns.
   *
   * @param user the owner
   * @return the conversations {@code user} owns, most recent activity first
   */
  List<SupportConversation> listConversationsOfUser(User user);

  /**
   * Lists the conversations linked to a Jira ticket, in any client.
   *
   * @param jiraKey the Jira issue key
   * @return every conversation linked to {@code jiraKey}
   */
  List<SupportConversation> listConversationsByJiraKey(String jiraKey);

  /**
   * Looks up one conversation linked to a Jira ticket, in any client.
   *
   * @param jiraKey the Jira issue key
   * @return one conversation linked to {@code jiraKey}, or {@code null}
   */
  SupportConversation findConversationByJiraKey(String jiraKey);

  /**
   * Lists the messages of a conversation.
   *
   * @param conversationId the conversation id
   * @return its messages, oldest first
   */
  List<SupportMessage> listMessages(String conversationId);

  /**
   * Lists the messages of a conversation that carry attachments, in any client.
   *
   * @param conversationId the conversation id
   * @return its messages with attachments
   */
  List<SupportMessage> listMessagesWithAttachments(String conversationId);

  /**
   * Lists the messages, in any client, whose attachments column mentions
   * {@code jiraAttachmentId}. A cheap pre-filter: the caller confirms the match by parsing the
   * column.
   *
   * @param jiraAttachmentId the Jira attachment id
   * @return the candidate messages
   */
  List<SupportMessage> listMessagesMentioningAttachment(String jiraAttachmentId);

  /**
   * Looks up the message stored for an external (Jira comment) id, in any client.
   *
   * @param externalId the external id
   * @return the message, or {@code null}
   */
  SupportMessage findMessageByExternalId(String externalId);

  /**
   * Creates a conversation without saving it.
   *
   * @return a new, unsaved conversation
   */
  SupportConversation newConversation();

  /**
   * Creates a message without saving it.
   *
   * @return a new, unsaved message
   */
  SupportMessage newMessage();

  /**
   * Saves a conversation in the current transaction.
   *
   * @param conversation the conversation to save
   */
  void saveConversation(SupportConversation conversation);

  /**
   * Saves a message in the current transaction.
   *
   * @param message the message to save
   */
  void saveMessage(SupportMessage message);

  /** Writes the pending saves to the database, in the current transaction. */
  void flush();

  /** Commits the current transaction and starts a new one. */
  void commitAndStart();
}
