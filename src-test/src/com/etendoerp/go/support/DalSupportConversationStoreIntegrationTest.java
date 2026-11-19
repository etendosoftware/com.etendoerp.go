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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * The support chat's persistence against the real DAL and tables: what a conversation and its
 * messages read back as, the order the endpoints list them in, which lookups honour the request's
 * tenant and which see every client (the Jira webhook and the attachment proxy carry no tenant),
 * and that the request context is entered and left balanced.
 *
 * <p>Nothing is committed: every test works inside its own transaction, which {@link #cleanUp()}
 * rolls back. Rows are created the way the servlet creates them, in the test client.</p>
 *
 * @covers com.etendoerp.go.support.DalSupportConversationStore
 */
public class DalSupportConversationStoreIntegrationTest extends OBBaseTest {

  private static final String SYSTEM = "0";
  private static final String OTHER_USER_ID = "100";
  private static final long MINUTE = 60_000L;

  private final DalSupportConversationStore store = new DalSupportConversationStore();
  private OBContext previous;

  @Before
  public void setUp() {
    setTestUserContext();
    previous = store.enterTenantAdminMode(TEST_USER_ID, TEST_ROLE_ID, TEST_CLIENT_ID, TEST_ORG_ID);
  }

  @After
  public void cleanUp() {
    try {
      store.restoreTenantContext(previous);
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  @Test
  public void aConversationAndItsMessagesReadBackOldestMessageFirst() {
    User user = store.findUser(TEST_USER_ID);
    Date now = new Date();
    SupportConversation conv = newConversation(user, now, null);
    newMessage(conv, user, "ai", "Second", now, null, null);
    newMessage(conv, user, "user", "First", new Date(now.getTime() - MINUTE), null, null);
    store.flush();
    OBDal.getInstance().getSession().clear();

    SupportConversation found = store.findConversation(conv.getId());
    assertEquals("Need help", found.getSubject());
    assertEquals("open", found.getStatus());
    assertEquals(TEST_USER_ID, found.getUser().getId());
    assertEquals(TEST_CLIENT_ID, found.getClient().getId());
    assertEquals(TEST_ORG_ID, found.getOrganization().getId());
    List<SupportMessage> messages = store.listMessages(conv.getId());
    assertEquals(2, messages.size());
    assertEquals("First", messages.get(0).getText());
    assertEquals("Second", messages.get(1).getText());
  }

  @Test
  public void aUsersConversationsAreListedMostRecentActivityFirstAndOnlyTheirs() {
    User user = store.findUser(TEST_USER_ID);
    Date now = new Date();
    SupportConversation older = newConversation(user, new Date(now.getTime() - MINUTE), null);
    SupportConversation newer = newConversation(user, now, null);
    SupportConversation someoneElses = newConversation(store.findUser(OTHER_USER_ID), now, null);
    store.flush();

    List<SupportConversation> listed = store.listConversationsOfUser(user);

    int newerAt = indexOf(listed, newer);
    int olderAt = indexOf(listed, older);
    assertTrue("both of the user's conversations are listed", newerAt >= 0 && olderAt >= 0);
    assertTrue("the most recent activity comes first", newerAt < olderAt);
    assertEquals("another user's conversation is not listed", -1, indexOf(listed, someoneElses));
  }

  @Test
  public void theJiraKeyAndAttachmentLookupsSeeEveryClientTheOwnerListDoesNot() {
    User user = store.findUser(TEST_USER_ID);
    String jiraKey = "IT-" + SupportConversationsServlet.newId().substring(0, 12);
    String attachmentId = "att-" + SupportConversationsServlet.newId();
    String externalId = "jira:" + jiraKey + ":c1";
    Date now = new Date();
    SupportConversation conv = newConversation(user, now, jiraKey);
    SupportMessage withAttachment = newMessage(conv, user, "human", "See attached", now, externalId,
        "[{\"id\":\"" + attachmentId + "\",\"filename\":\"a.png\",\"mimeType\":\"image/png\"}]");
    newMessage(conv, user, "user", "No file", now, null, null);
    store.flush();

    OBContext.setOBContext(SYSTEM, SYSTEM, SYSTEM, SYSTEM);
    store.enterAdminMode();
    try {
      assertTrue("the owner list honours the context's readable clients",
          store.listConversationsOfUser(user).isEmpty());
      assertSame(conv, store.findConversationByJiraKey(jiraKey));
      assertEquals(List.of(conv), store.listConversationsByJiraKey(jiraKey));
      assertSame(withAttachment, store.findMessageByExternalId(externalId));
      assertEquals(List.of(withAttachment), store.listMessagesMentioningAttachment(attachmentId));
      assertEquals("only the message carrying attachments", List.of(withAttachment),
          store.listMessagesWithAttachments(conv.getId()));
    } finally {
      store.restorePreviousMode();
    }
  }

  @Test
  public void lookupsOfRowsThatDoNotExistAnswerNothing() {
    String unknown = SupportConversationsServlet.newId();

    assertNull(store.findConversation(unknown));
    assertNull(store.findConversationByJiraKey("IT-" + unknown));
    assertTrue(store.listConversationsByJiraKey("IT-" + unknown).isEmpty());
    assertNull(store.findMessageByExternalId("jira:" + unknown));
    assertTrue(store.listMessagesMentioningAttachment(unknown).isEmpty());
    assertTrue(store.listMessages(unknown).isEmpty());
  }

  @Test
  public void theTenantContextIsEnteredInAdminModeAndRestoredBalanced() {
    OBContext outer = OBContext.getOBContext();

    OBContext before = store.enterTenantAdminMode(OTHER_USER_ID, SYSTEM, SYSTEM, SYSTEM);
    try {
      assertSame(outer, before);
      assertEquals(OTHER_USER_ID, OBContext.getOBContext().getUser().getId());
      assertEquals(SYSTEM, OBContext.getOBContext().getCurrentClient().getId());
      assertTrue(OBContext.getOBContext().isInAdministratorMode());
    } finally {
      store.restoreTenantContext(before);
    }

    assertSame(outer, OBContext.getOBContext());
    assertEquals(TEST_CLIENT_ID, OBContext.getOBContext().getCurrentClient().getId());
  }

  @Test
  public void adminModeIsLeftWhereItWasEntered() {
    store.restorePreviousMode();
    try {
      assertFalse(OBContext.getOBContext().isInAdministratorMode());
      store.enterAdminMode();
      try {
        assertTrue(OBContext.getOBContext().isInAdministratorMode());
      } finally {
        store.restorePreviousMode();
      }
      assertFalse(OBContext.getOBContext().isInAdministratorMode());
    } finally {
      OBContext.setAdminMode(true);
    }
  }

  /** Same fields the servlet sets when a conversation starts. */
  private SupportConversation newConversation(User user, Date lastActivity, String jiraKey) {
    SupportConversation conv = store.newConversation();
    conv.setNewOBObject(true);
    conv.setId(SupportConversationsServlet.newId());
    conv.setClient(store.findClient(TEST_CLIENT_ID));
    conv.setOrganization(store.findOrganization(TEST_ORG_ID));
    conv.setUser(user);
    conv.setSubject("Need help");
    conv.setStatus("open");
    conv.setLastActivity(lastActivity);
    conv.setJiraTicketKey(jiraKey);
    store.saveConversation(conv);
    return conv;
  }

  private SupportMessage newMessage(SupportConversation conv, User createdBy, String sender,
      String text, Date date, String externalId, String attachments) {
    SupportMessage msg = store.newMessage();
    msg.setNewOBObject(true);
    msg.setId(SupportConversationsServlet.newId());
    msg.setClient(conv.getClient());
    msg.setOrganization(conv.getOrganization());
    msg.setCreatedBy(createdBy);
    msg.setUpdatedBy(createdBy);
    msg.setConversation(conv);
    msg.setSender(sender);
    msg.setSenderName(sender);
    msg.setText(text);
    msg.setMessageDate(date);
    msg.setExternalId(externalId);
    msg.setAttachments(attachments);
    store.saveMessage(msg);
    return msg;
  }

  private static int indexOf(List<SupportConversation> conversations, SupportConversation wanted) {
    for (int i = 0; i < conversations.size(); i++) {
      if (wanted.getId().equals(conversations.get(i).getId())) {
        return i;
      }
    }
    return -1;
  }
}
