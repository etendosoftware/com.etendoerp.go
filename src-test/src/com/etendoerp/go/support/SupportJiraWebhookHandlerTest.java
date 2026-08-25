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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SupportConversation;
import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * Tests for {@link SupportJiraWebhookHandler}.
 */
class SupportJiraWebhookHandlerTest {

  private static final String IGNORED_BODY = "{\"status\":\"ignored\"}";

  private static HttpServletResponse mockResponse(StringWriter capture) throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(capture));
    return response;
  }

  private static HttpServletRequest mockRequestWithBody(String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body == null ? "" : body)));
    return request;
  }

  private static OBDal mockObDal(MockedStatic<OBDal> dalMock) {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    return obDal;
  }

  private static SupportConversation mockConversation(String id) {
    SupportConversation conv = mock(SupportConversation.class);
    when(conv.getId()).thenReturn(id);
    when(conv.getClient()).thenReturn(mock(Client.class));
    when(conv.getOrganization()).thenReturn(mock(Organization.class));
    return conv;
  }

  @SuppressWarnings("unchecked")
  private static <T extends BaseOBObject> void mockCriteria(OBDal obDal, Class<T> clazz, List<T> results) {
    OBCriteria<T> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(clazz)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenReturn(crit);
    when(crit.list()).thenReturn(results);
    when(crit.uniqueResult()).thenReturn(results.isEmpty() ? null : results.get(0));
  }

  // -------------------------------------------------------------------------
  // isStatusTransitionToDone
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isStatusTransitionToDone")
  class IsStatusTransitionToDone {

    @Test
    @DisplayName("Null changelog returns false")
    void nullChangelog() {
      assertFalse(SupportJiraWebhookHandler.isStatusTransitionToDone(null));
    }

    @Test
    @DisplayName("Changelog without items array returns false")
    void noItemsArray() throws Exception {
      assertFalse(SupportJiraWebhookHandler.isStatusTransitionToDone(new JSONObject().put("id", "1")));
    }

    @Test
    @DisplayName("Items with unrelated field return false")
    void unrelatedField() throws Exception {
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("field", "assignee").put("toString", "Done"));
      JSONObject changelog = new JSONObject().put("items", items);
      assertFalse(SupportJiraWebhookHandler.isStatusTransitionToDone(changelog));
    }

    @Test
    @DisplayName("A status item transitioning to Done (case-insensitive) returns true")
    void statusToDone() throws Exception {
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("field", "status").put("toString", "done"));
      JSONObject changelog = new JSONObject().put("items", items);
      assertTrue(SupportJiraWebhookHandler.isStatusTransitionToDone(changelog));
    }

    @Test
    @DisplayName("A matching item that is not first in the array is still found")
    void matchNotFirst() throws Exception {
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("field", "priority").put("toString", "High"));
      items.put(new JSONObject().put("field", "status").put("toString", "Done"));
      JSONObject changelog = new JSONObject().put("items", items);
      assertTrue(SupportJiraWebhookHandler.isStatusTransitionToDone(changelog));
    }

    @Test
    @DisplayName("Status transition to a non-Done value returns false")
    void statusToOther() throws Exception {
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("field", "status").put("toString", "In Progress"));
      JSONObject changelog = new JSONObject().put("items", items);
      assertFalse(SupportJiraWebhookHandler.isStatusTransitionToDone(changelog));
    }
  }

  // -------------------------------------------------------------------------
  // nvl
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("nvl")
  class Nvl {
    @Test
    @DisplayName("Null value returns fallback")
    void nullValue() {
      assertEquals("fallback", SupportJiraWebhookHandler.nvl(null, "fallback"));
    }

    @Test
    @DisplayName("Empty value returns fallback")
    void emptyValue() {
      assertEquals("fallback", SupportJiraWebhookHandler.nvl("", "fallback"));
    }

    @Test
    @DisplayName("Non-empty value is returned as-is")
    void nonEmptyValue() {
      assertEquals("value", SupportJiraWebhookHandler.nvl("value", "fallback"));
    }
  }

  // -------------------------------------------------------------------------
  // isBotEmail
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isBotEmail")
  class IsBotEmail {

    @Test
    @DisplayName("Null email returns false")
    void nullEmail() {
      assertFalse(SupportJiraWebhookHandler.isBotEmail(null));
    }

    @Test
    @DisplayName("Empty email returns false")
    void emptyEmail() {
      assertFalse(SupportJiraWebhookHandler.isBotEmail(""));
    }

    @Test
    @DisplayName("Email matching the configured Jira username returns true, case-insensitively")
    void matchesJiraUsername() {
      // JiraConfig ships no hardcoded default username (see JiraConfig javadoc) — set it
      // explicitly here rather than relying on one, and restore the prior value afterward so
      // this doesn't leak into other tests in the same JVM.
      System.setProperty(JiraConfig.PROP_USERNAME, "info@smfconsulting.es");
      try {
        assertTrue(SupportJiraWebhookHandler.isBotEmail("INFO@SMFCONSULTING.ES"));
        assertTrue(SupportJiraWebhookHandler.isBotEmail("info@smfconsulting.es"));
      } finally {
        System.clearProperty(JiraConfig.PROP_USERNAME);
      }
    }

    @Test
    @DisplayName("Non-matching email returns false")
    void nonMatchingEmail() {
      assertFalse(SupportJiraWebhookHandler.isBotEmail("someone.else@example.com"));
    }
  }

  // -------------------------------------------------------------------------
  // isBlockType
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isBlockType")
  class IsBlockType {
    @Test
    @DisplayName("Recognized block types")
    void blockTypes() {
      assertTrue(SupportJiraWebhookHandler.isBlockType("paragraph"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("heading"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("bulletList"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("orderedList"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("listItem"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("codeBlock"));
      assertTrue(SupportJiraWebhookHandler.isBlockType("blockquote"));
    }

    @Test
    @DisplayName("Non-block types return false")
    void nonBlockTypes() {
      assertFalse(SupportJiraWebhookHandler.isBlockType("text"));
      assertFalse(SupportJiraWebhookHandler.isBlockType("hardBreak"));
      assertFalse(SupportJiraWebhookHandler.isBlockType("mention"));
      assertFalse(SupportJiraWebhookHandler.isBlockType("doc"));
    }
  }

  // -------------------------------------------------------------------------
  // extractAdfText / extractAdfTextFromString / extractAdfTextFromObject
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("extractAdfText family")
  class ExtractAdfText {

    @Test
    @DisplayName("Null node returns empty string")
    void nullNode() {
      assertEquals("", SupportJiraWebhookHandler.extractAdfText(null));
    }

    @Test
    @DisplayName("Unsupported node type returns empty string")
    void unsupportedType() {
      assertEquals("", SupportJiraWebhookHandler.extractAdfText(Integer.valueOf(42)));
    }

    @Test
    @DisplayName("Plain non-JSON string is returned trimmed")
    void plainString() {
      assertEquals("Just plain text", SupportJiraWebhookHandler.extractAdfText("  Just plain text  "));
    }

    @Test
    @DisplayName("A JSON-string that parses to an ADF object is parsed recursively")
    void jsonString() {
      String raw = "{\"type\":\"text\",\"text\":\"hi there\"}";
      assertEquals("hi there", SupportJiraWebhookHandler.extractAdfText(raw));
    }

    @Test
    @DisplayName("Malformed JSON string starting with '{' falls back to the trimmed raw string")
    void malformedJsonString() {
      String raw = "  {not valid json  ";
      assertEquals("{not valid json", SupportJiraWebhookHandler.extractAdfText(raw));
    }

    @Test
    @DisplayName("type:text leaf node returns its text")
    void textLeaf() throws Exception {
      JSONObject node = new JSONObject().put("type", "text").put("text", "leaf value");
      assertEquals("leaf value", SupportJiraWebhookHandler.extractAdfText(node));
    }

    @Test
    @DisplayName("type:hardBreak returns a newline")
    void hardBreak() throws Exception {
      JSONObject node = new JSONObject().put("type", "hardBreak");
      assertEquals("\n", SupportJiraWebhookHandler.extractAdfText(node));
    }

    @Test
    @DisplayName("Block types append a trailing newline when content doesn't already end in one")
    void blockTypeAppendsNewline() throws Exception {
      for (String blockType : new String[] {
          "paragraph", "heading", "bulletList", "orderedList", "listItem", "codeBlock", "blockquote" }) {
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", "content of " + blockType));
        JSONObject node = new JSONObject().put("type", blockType).put("content", content);
        String result = SupportJiraWebhookHandler.extractAdfText(node);
        assertEquals("content of " + blockType + "\n", result, "block type: " + blockType);
      }
    }

    @Test
    @DisplayName("Block type does not double up the trailing newline when content already ends in one")
    void blockTypeDoesNotDoubleNewline() throws Exception {
      JSONArray content = new JSONArray();
      content.put(new JSONObject().put("type", "text").put("text", "line1"));
      content.put(new JSONObject().put("type", "hardBreak"));
      JSONObject node = new JSONObject().put("type", "paragraph").put("content", content);
      assertEquals("line1\n", SupportJiraWebhookHandler.extractAdfText(node));
    }

    @Test
    @DisplayName("Non-block type does not append a trailing newline")
    void nonBlockTypeNoNewline() throws Exception {
      JSONArray content = new JSONArray();
      content.put(new JSONObject().put("type", "text").put("text", "@user"));
      JSONObject node = new JSONObject().put("type", "mention").put("content", content);
      assertEquals("@user", SupportJiraWebhookHandler.extractAdfText(node));
    }

    @Test
    @DisplayName("Nested content arrays recurse correctly")
    void nestedContent() throws Exception {
      JSONArray innerContent = new JSONArray();
      innerContent.put(new JSONObject().put("type", "text").put("text", "Hello "));
      innerContent.put(new JSONObject().put("type", "text").put("text", "world"));
      JSONObject paragraph = new JSONObject().put("type", "paragraph").put("content", innerContent);

      JSONArray docContent = new JSONArray();
      docContent.put(paragraph);
      JSONObject doc = new JSONObject().put("type", "doc").put("content", docContent);

      assertEquals("Hello world\n", SupportJiraWebhookHandler.extractAdfText(doc));
    }

    @Test
    @DisplayName("Object with no content array and unrecognized type returns empty string")
    void noContentArray() throws Exception {
      JSONObject node = new JSONObject().put("type", "unknown");
      assertEquals("", SupportJiraWebhookHandler.extractAdfText(node));
    }
  }

  // -------------------------------------------------------------------------
  // parseStandardJiraWebhook
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseStandardJiraWebhook")
  class ParseStandardJiraWebhook {

    @Test
    @DisplayName("Missing issue object is ignored")
    void missingIssue() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject body = new JSONObject();

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNull(result);
      assertEquals(IGNORED_BODY, capture.toString());
    }

    @Test
    @DisplayName("Empty jiraKey is ignored")
    void emptyJiraKey() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject body = new JSONObject().put("issue", new JSONObject().put("key", ""));

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNull(result);
      assertEquals(IGNORED_BODY, capture.toString());
    }

    @Test
    @DisplayName("No comment field with no matching non-comment event is ignored")
    void noCommentNoMatchingEvent() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject body = new JSONObject().put("issue", new JSONObject().put("key", "SUP-1"));

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNull(result);
      assertEquals(IGNORED_BODY, capture.toString());
    }

    @Test
    @DisplayName("jsdPublic=false comment is skipped as internal")
    void internalComment() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject comment = new JSONObject().put("id", "c1").put("jsdPublic", false);
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-1"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNull(result);
      assertEquals("{\"status\":\"skipped_internal\"}", capture.toString());
    }

    @Test
    @DisplayName("Valid public comment is parsed into a JiraWebhookComment")
    void validComment() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject author = new JSONObject().put("emailAddress", "agent@example.com").put("displayName", "Agent Smith");
      JSONObject comment = new JSONObject()
          .put("id", "c42")
          .put("jsdPublic", true)
          .put("author", author)
          .put("body", "  Thanks for reaching out  ");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-7"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertEquals("SUP-7", result.jiraKey);
      assertEquals("c42", result.commentId);
      assertEquals("Agent Smith", result.authorName);
      assertEquals("agent@example.com", result.authorEmail);
      assertEquals("Thanks for reaching out", result.text);
    }

    @Test
    @DisplayName("Author accountId is parsed from the comment author object into the resulting comment")
    void authorAccountIdParsed() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      JSONObject author = new JSONObject().put("emailAddress", "agent@example.com")
          .put("displayName", "Agent Smith").put("accountId", "acc-123");
      JSONObject comment = new JSONObject()
          .put("id", "c43")
          .put("jsdPublic", true)
          .put("author", author)
          .put("body", "Thanks");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-43"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertEquals("acc-123", result.authorAccountId);
    }

    @Test
    @DisplayName("Missing accountId on the author object defaults to an empty string, not null")
    void missingAuthorAccountIdDefaultsToEmpty() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      JSONObject author = new JSONObject().put("emailAddress", "agent@example.com").put("displayName", "Agent Smith");
      JSONObject comment = new JSONObject()
          .put("id", "c44").put("jsdPublic", true).put("author", author).put("body", "Thanks");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-44"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertEquals("", result.authorAccountId);
    }

    @Test
    @DisplayName("Missing comment id falls back to a generated id")
    void missingCommentId() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      JSONObject comment = new JSONObject().put("jsdPublic", true).put("body", "hi");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-8"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertNotNull(result.commentId);
      assertTrue(result.commentId.matches("[0-9a-f]{32}"));
    }

    @Test
    @DisplayName("Missing author defaults to the generic agent name and empty email")
    void missingAuthor() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      JSONObject comment = new JSONObject().put("id", "c1").put("jsdPublic", true).put("body", "hi");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-9"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertEquals("Agente de soporte", result.authorName);
      assertEquals("", result.authorEmail);
    }

    @Test
    @DisplayName("Wiki-markup embedded image reference in the comment body is preserved verbatim when "
        + "attachment correlation is unavailable (no Jira token configured in this test environment) — "
        + "an unresolved reference is never silently swallowed")
    void wikiMarkupBodyNoTokenConfigured() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      JSONObject comment = new JSONObject()
          .put("id", "c50")
          .put("jsdPublic", true)
          .put("body", "!Captura desde 2026-07-15 13-21-04.png|width=989,"
              + "alt=\"Captura desde 2026-07-15 13-21-04.png\"!");
      JSONObject body = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-50"))
          .put("comment", comment);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseStandardJiraWebhook(response, body);

      assertNotNull(result);
      assertTrue(result.text.contains("!Captura desde 2026-07-15 13-21-04.png"));
      assertNull(result.attachments);
    }
  }

  // -------------------------------------------------------------------------
  // handleJiraNonCommentEvent
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleJiraNonCommentEvent")
  class HandleJiraNonCommentEvent {

    @Test
    @DisplayName("No fields, no changelog: ignored")
    void noFieldsNoChangelog() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject issue = new JSONObject();
      JSONObject body = new JSONObject();

      SupportJiraWebhookHandler.handleJiraNonCommentEvent(response, issue, body, "SUP-1");

      assertEquals(IGNORED_BODY, capture.toString());
    }

    @Test
    @DisplayName("Assignee reset to bot email triggers human takeover reset")
    void assigneeIsBot() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject assignee = new JSONObject().put("emailAddress", "info@smfconsulting.es");
      JSONObject fields = new JSONObject().put("assignee", assignee);
      JSONObject issue = new JSONObject().put("fields", fields);
      JSONObject body = new JSONObject();

      // JiraConfig ships no hardcoded default username (see its javadoc) — configure it
      // explicitly so isBotEmail recognizes this address, and restore afterward.
      System.setProperty(JiraConfig.PROP_USERNAME, "info@smfconsulting.es");
      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportJiraWebhookHandler.handleJiraNonCommentEvent(response, issue, body, "SUP-1");
      } finally {
        System.clearProperty(JiraConfig.PROP_USERNAME);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
      assertTrue(capture.toString().contains("SUP-1"));
    }

    @Test
    @DisplayName("Assignee reset with NO emailAddress (private Atlassian profile) still triggers "
        + "human takeover reset, via the assignee's displayName")
    void assigneeIsBotWithPrivateEmailFallsBackToDisplayName() throws Exception {
      // Regression test: "Information Etendo" has email visibility set to private in its
      // Atlassian profile, so Jira Cloud omits emailAddress from the assignee object entirely —
      // isBotEmail(assigneeEmail) alone would silently never match, leaving human_takeover
      // stuck true forever even after a human reassigns the ticket back to the bot.
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject assignee = new JSONObject().put("displayName", "Information Etendo");
      JSONObject fields = new JSONObject().put("assignee", assignee);
      JSONObject issue = new JSONObject().put("fields", fields);
      JSONObject body = new JSONObject();

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportJiraWebhookHandler.handleJiraNonCommentEvent(response, issue, body, "SUP-1");
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
      assertTrue(capture.toString().contains("SUP-1"));
    }

    @Test
    @DisplayName("Status transition to Done triggers ticket closed handling")
    void statusDone() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject issue = new JSONObject();
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("field", "status").put("toString", "Done"));
      JSONObject changelog = new JSONObject().put("items", items);
      JSONObject body = new JSONObject().put("changelog", changelog);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList()); // no matching conversation

        SupportJiraWebhookHandler.handleJiraNonCommentEvent(response, issue, body, "SUP-2");
      }

      assertTrue(capture.toString().contains("no_conversation"));
    }

    @Test
    @DisplayName("Assignee not bot and no status transition to Done: ignored")
    void assigneeNotBotAndNoStatusChange() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject assignee = new JSONObject().put("emailAddress", "other.person@example.com");
      JSONObject fields = new JSONObject().put("assignee", assignee);
      JSONObject issue = new JSONObject().put("fields", fields);
      JSONObject body = new JSONObject();

      SupportJiraWebhookHandler.handleJiraNonCommentEvent(response, issue, body, "SUP-3");

      assertEquals(IGNORED_BODY, capture.toString());
    }
  }

  // -------------------------------------------------------------------------
  // handleAssigneeReset / handleTicketClosed
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleAssigneeReset / handleTicketClosed")
  class AssigneeAndTicketHandlers {

    @Test
    @DisplayName("handleAssigneeReset writes ok on success")
    void assigneeResetSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportJiraWebhookHandler.handleAssigneeReset(response, "SUP-1");
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("handleAssigneeReset writes an internal error when the DB call fails")
    void assigneeResetFailure() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        dalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("db down"));

        SupportJiraWebhookHandler.handleAssigneeReset(response, "SUP-1");
      }

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("handleTicketClosed writes no_conversation when nothing matches")
    void ticketClosedNoConversation() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList());

        SupportJiraWebhookHandler.handleTicketClosed(response, "SUP-4");
      }

      assertTrue(capture.toString().contains("no_conversation"));
    }

    @Test
    @DisplayName("handleTicketClosed closes the matching conversation")
    void ticketClosedSuccess() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-99")));

        SupportJiraWebhookHandler.handleTicketClosed(response, "SUP-5");
      }

      assertTrue(capture.toString().contains("conv-99"));
      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("handleTicketClosed writes an internal error when the DB call fails")
    void ticketClosedFailure() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        dalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("db down"));

        SupportJiraWebhookHandler.handleTicketClosed(response, "SUP-6");
      }

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  // -------------------------------------------------------------------------
  // parseAutomationJiraWebhook
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseAutomationJiraWebhook")
  class ParseAutomationJiraWebhook {

    @Test
    @DisplayName("Missing issueKey is ignored")
    void missingIssueKey() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

      assertNull(result);
      assertEquals("{\"status\":\"ignored_no_key\"}", capture.toString());
    }

    @Test
    @DisplayName("assignee_reset action from the bot email triggers reset")
    void assigneeResetFromBot() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-10");
      when(request.getParameter("action")).thenReturn("assignee_reset");
      when(request.getParameter("authorEmail")).thenReturn("info@smfconsulting.es");

      // JiraConfig ships no hardcoded default username (see its javadoc) — configure it
      // explicitly so isBotEmail recognizes this address, and restore afterward.
      System.setProperty(JiraConfig.PROP_USERNAME, "info@smfconsulting.es");
      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportJiraWebhookHandler.JiraWebhookComment result =
            SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

        assertNull(result);
      } finally {
        System.clearProperty(JiraConfig.PROP_USERNAME);
      }
      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("assignee_reset action with no authorEmail still triggers reset via authorName "
        + "(same private-email fallback as the standard webhook path)")
    void assigneeResetFromBotWithNoEmailFallsBackToAuthorName() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-11");
      when(request.getParameter("action")).thenReturn("assignee_reset");
      when(request.getParameter("authorName")).thenReturn("Information Etendo");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportJiraWebhookHandler.JiraWebhookComment result =
            SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

        assertNull(result);
      }
      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("assignee_reset action from a non-bot email is ignored")
    void assigneeResetFromNonBot() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-11");
      when(request.getParameter("action")).thenReturn("assignee_reset");
      when(request.getParameter("authorEmail")).thenReturn("someone@example.com");

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

      assertNull(result);
      assertEquals("{\"status\":\"ignored_not_bot\"}", capture.toString());
    }

    @Test
    @DisplayName("ticket_closed action triggers the ticket-closed handler")
    void ticketClosedAction() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-12");
      when(request.getParameter("action")).thenReturn("ticket_closed");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList());

        SupportJiraWebhookHandler.JiraWebhookComment result =
            SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

        assertNull(result);
      }
      assertTrue(capture.toString().contains("no_conversation"));
    }

    @Test
    @DisplayName("Default action returns a JiraWebhookComment built from query params")
    void defaultActionBuildsComment() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-13");
      when(request.getParameter("commentId")).thenReturn("cid-1");
      when(request.getParameter("authorName")).thenReturn("Jane Doe");
      when(request.getParameter("authorEmail")).thenReturn("jane@example.com");
      when(request.getParameter("commentText")).thenReturn("Hello from automation");

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

      assertNotNull(result);
      assertEquals("SUP-13", result.jiraKey);
      assertEquals("cid-1", result.commentId);
      assertEquals("Jane Doe", result.authorName);
      assertEquals("jane@example.com", result.authorEmail);
      assertEquals("Hello from automation", result.text);
    }

    @Test
    @DisplayName("Default action falls back to defaults when optional params are missing")
    void defaultActionFallbacks() throws Exception {
      HttpServletResponse response = mockResponse(new StringWriter());
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getParameter("issueKey")).thenReturn("SUP-14");

      SupportJiraWebhookHandler.JiraWebhookComment result =
          SupportJiraWebhookHandler.parseAutomationJiraWebhook(request, response);

      assertNotNull(result);
      assertEquals("SUP-14", result.jiraKey);
      assertNotNull(result.commentId);
      assertTrue(result.commentId.matches("[0-9a-f]{32}"));
      assertEquals("Agente de soporte", result.authorName);
      assertEquals("", result.authorEmail);
      assertEquals("", result.text);
    }
  }

  // -------------------------------------------------------------------------
  // writeIgnored
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("writeIgnored writes the ignored status payload with HTTP 200")
  void writeIgnoredWritesExpectedPayload() throws Exception {
    StringWriter capture = new StringWriter();
    HttpServletResponse response = mockResponse(capture);

    SupportJiraWebhookHandler.writeIgnored(response);

    verify(response).setStatus(200);
    assertEquals(IGNORED_BODY, capture.toString());
  }

  // -------------------------------------------------------------------------
  // collectAdfMediaIds
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("collectAdfMediaIds")
  class CollectAdfMediaIds {

    @Test
    @DisplayName("A comment with no media nodes collects nothing")
    void noMediaNodes() throws Exception {
      JSONArray content = new JSONArray();
      content.put(new JSONObject().put("type", "text").put("text", "just plain text"));
      JSONObject paragraph = new JSONObject().put("type", "paragraph").put("content", content);
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(paragraph, ids);

      assertTrue(ids.isEmpty());
    }

    @Test
    @DisplayName("A single media node's attrs.id is collected")
    void singleMediaNode() throws Exception {
      JSONObject media = new JSONObject().put("type", "media")
          .put("attrs", new JSONObject().put("id", "att-1"));
      JSONArray content = new JSONArray().put(media);
      JSONObject mediaSingle = new JSONObject().put("type", "mediaSingle").put("content", content);
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(mediaSingle, ids);

      assertEquals(List.of("att-1"), ids);
    }

    @Test
    @DisplayName("Multiple media nodes nested under a mediaGroup are all collected, in order")
    void multipleMediaNodes() throws Exception {
      JSONObject media1 = new JSONObject().put("type", "media").put("attrs", new JSONObject().put("id", "att-1"));
      JSONObject media2 = new JSONObject().put("type", "media").put("attrs", new JSONObject().put("id", "att-2"));
      JSONArray content = new JSONArray().put(media1).put(media2);
      JSONObject mediaGroup = new JSONObject().put("type", "mediaGroup").put("content", content);
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(mediaGroup, ids);

      assertEquals(List.of("att-1", "att-2"), ids);
    }

    @Test
    @DisplayName("A media node with a blank attrs.id is skipped")
    void blankMediaId() throws Exception {
      JSONObject media = new JSONObject().put("type", "media").put("attrs", new JSONObject().put("id", ""));
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(media, ids);

      assertTrue(ids.isEmpty());
    }

    @Test
    @DisplayName("A media node with no attrs at all is skipped, not an NPE")
    void missingAttrs() throws Exception {
      JSONObject media = new JSONObject().put("type", "media");
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(media, ids);

      assertTrue(ids.isEmpty());
    }

    @Test
    @DisplayName("String-encoded ADF body (the production webhook shape) is parsed the same way")
    void stringEncodedBody() throws Exception {
      String raw = "{\"type\":\"doc\",\"content\":[{\"type\":\"mediaSingle\",\"content\":"
          + "[{\"type\":\"media\",\"attrs\":{\"id\":\"att-9\"}}]}]}";
      List<String> ids = new ArrayList<>();

      SupportAdfAttachmentCorrelator.collectAdfMediaIds(raw, ids);

      assertEquals(List.of("att-9"), ids);
    }
  }

  // -------------------------------------------------------------------------
  // parseJiraInstantMillis / parseCommentTimestamp
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseJiraInstantMillis / parseCommentTimestamp")
  class ParseTimestamps {

    @Test
    @DisplayName("ISO offset date-time string (colon offset) parses to the correct epoch millis")
    void isoOffsetDateTime() {
      long millis = SupportJiraWebhookHandler.parseJiraInstantMillis("2026-07-14T10:00:00.000-03:00");
      assertTrue(millis > 0);
    }

    @Test
    @DisplayName("Plain Instant-parseable string (Z suffix) is also accepted")
    void instantStyle() {
      long millis = SupportJiraWebhookHandler.parseJiraInstantMillis("2026-07-14T13:00:00Z");
      assertTrue(millis > 0);
    }

    @Test
    @DisplayName("Null or empty value returns -1")
    void nullOrEmpty() {
      assertEquals(-1, SupportJiraWebhookHandler.parseJiraInstantMillis(null));
      assertEquals(-1, SupportJiraWebhookHandler.parseJiraInstantMillis(""));
    }

    @Test
    @DisplayName("Unparseable garbage value returns -1")
    void unparseable() {
      assertEquals(-1, SupportJiraWebhookHandler.parseJiraInstantMillis("not-a-date"));
    }

    /**
     * FIXED: Jira Cloud's REST API v3 actually formats dates (issue/comment/attachment
     * {@code created}) with a numeric offset WITHOUT a colon, e.g.
     * {@code "2021-01-05T10:15:30.000+0000"} — this is Jira Cloud's well-documented date shape,
     * not a hypothetical. {@code parseJiraInstantMillis} now also tries a {@code Z}-pattern
     * formatter that accepts this no-colon offset shape, so real Jira timestamps parse correctly
     * instead of always returning -1.
     */
    @Test
    @DisplayName("FIXED: Jira Cloud's real no-colon offset date format ('+0000') now parses correctly")
    void realJiraDateFormatParsesCorrectly() {
      long millis = SupportJiraWebhookHandler.parseJiraInstantMillis("2021-01-05T10:15:30.000+0000");
      assertEquals(1_609_841_730_000L, millis);
    }

    @Test
    @DisplayName("Comment with a valid ISO 'created' field uses it verbatim")
    void commentWithCreated() throws Exception {
      JSONObject comment = new JSONObject().put("created", "2026-07-14T13:00:00.000Z");
      Date result = SupportJiraWebhookHandler.parseCommentTimestamp(comment);
      assertEquals(SupportJiraWebhookHandler.parseJiraInstantMillis("2026-07-14T13:00:00.000Z"), result.getTime());
    }

    @Test
    @DisplayName("Comment without a 'created' field falls back to 'now' rather than throwing")
    void commentWithoutCreatedFallsBackToNow() {
      JSONObject comment = new JSONObject();
      long before = System.currentTimeMillis();
      Date result = SupportJiraWebhookHandler.parseCommentTimestamp(comment);
      long after = System.currentTimeMillis();
      assertTrue(result.getTime() >= before && result.getTime() <= after);
    }

    @Test
    @DisplayName("FIXED: Comment with Jira's real no-colon 'created' format now uses it verbatim")
    void commentWithRealJiraCreatedUsesItVerbatim() throws Exception {
      JSONObject comment = new JSONObject().put("created", "2021-01-05T10:15:30.000+0000");
      Date result = SupportJiraWebhookHandler.parseCommentTimestamp(comment);
      assertEquals(1_609_841_730_000L, result.getTime());
    }
  }

  // -------------------------------------------------------------------------
  // toAttachmentMeta
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("toAttachmentMeta")
  class ToAttachmentMeta {

    @Test
    @DisplayName("Applies defaults when filename/mimeType are absent")
    void defaults() throws Exception {
      JSONObject att = new JSONObject().put("id", "10001");
      JSONObject meta = SupportAdfAttachmentCorrelator.toAttachmentMeta(att);
      assertEquals("10001", meta.getString("id"));
      assertEquals("attachment", meta.getString("filename"));
      assertEquals("application/octet-stream", meta.getString("mimeType"));
    }

    @Test
    @DisplayName("Preserves the real filename/mimeType when present")
    void realValues() throws Exception {
      JSONObject att = new JSONObject().put("id", "10001").put("filename", "screenshot.png")
          .put("mimeType", "image/png");
      JSONObject meta = SupportAdfAttachmentCorrelator.toAttachmentMeta(att);
      assertEquals("screenshot.png", meta.getString("filename"));
      assertEquals("image/png", meta.getString("mimeType"));
    }
  }

  // -------------------------------------------------------------------------
  // correlateAttachments — the ADF-media-id ↔ Jira-REST-attachment correlation logic
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("correlateAttachments")
  class CorrelateAttachments {

    private JSONObject attachment(String id, String filename, String mimeType, String created) throws Exception {
      return new JSONObject().put("id", id).put("filename", filename).put("mimeType", mimeType)
          .put("created", created);
    }

    private String isoOf(long epochMillis) {
      return java.time.Instant.ofEpochMilli(epochMillis).toString();
    }

    @Test
    @DisplayName("Empty media ids yields an empty result")
    void noMediaIds() {
      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(new JSONArray(), List.of(), new Date());
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("Empty issue attachments yields an empty result even with media ids present")
    void noIssueAttachments() {
      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(new JSONArray(), List.of("m1"), new Date());
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("A media id matching an attachment id directly resolves with the right {id, filename, mimeType}")
    void directIdMatch() throws Exception {
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("10001", "screenshot.png", "image/png", isoOf(1_800_000_000_000L)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("10001"), new Date());

      assertEquals(1, result.length());
      JSONObject meta = result.getJSONObject(0);
      assertEquals("10001", meta.getString("id"));
      assertEquals("screenshot.png", meta.getString("filename"));
      assertEquals("image/png", meta.getString("mimeType"));
    }

    @Test
    @DisplayName("Multiple media ids in one comment are ALL correlated, not just the first")
    void multipleDirectMatches() throws Exception {
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("10001", "a.png", "image/png", isoOf(1_800_000_000_000L)))
          .put(attachment("10002", "b.pdf", "application/pdf", isoOf(1_800_000_005_000L)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("10001", "10002"), new Date());

      assertEquals(2, result.length());
      assertEquals("10001", result.getJSONObject(0).getString("id"));
      assertEquals("10002", result.getJSONObject(1).getString("id"));
    }

    @Test
    @DisplayName("A media id with no direct match falls back to the closest-by-timestamp unclaimed attachment")
    void fallbackPicksClosestByTime() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      JSONArray issueAttachments = new JSONArray()
          // 1 hour before the comment
          .put(attachment("A1", "far.png", "image/png", isoOf(commentTime.getTime() - 3_600_000)))
          // 2 seconds after the comment — the closest one
          .put(attachment("A2", "close.png", "image/png", isoOf(commentTime.getTime() + 2_000)));

      // "adf-media-xyz" stands in for a Media Platform file id that never appears verbatim in the
      // REST attachment list — this forces the fallback path.
      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("adf-media-xyz"), commentTime);

      assertEquals(1, result.length());
      assertEquals("A2", result.getJSONObject(0).getString("id"));
    }

    @Test
    @DisplayName("Fallback does not re-claim an attachment already resolved by a direct id match")
    void fallbackSkipsAlreadyClaimedAttachment() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      // "10002" is both the direct match AND would be the closest-by-time candidate for the
      // fallback — the fallback for the unmatched id must not double-claim it. A3 is 5 minutes
      // away, comfortably inside the 15-minute fallback distance threshold.
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("10002", "close.png", "image/png", isoOf(commentTime.getTime())))
          .put(attachment("A3", "far.png", "image/png", isoOf(commentTime.getTime() - 300_000)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("10002", "adf-media-xyz"), commentTime);

      assertEquals(2, result.length());
      assertEquals("10002", result.getJSONObject(0).getString("id"));
      assertEquals("A3", result.getJSONObject(1).getString("id"));
    }

    @Test
    @DisplayName("More unmatched media ids than available attachments: only what actually exists is returned")
    void moreMediaIdsThanAttachments() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("A1", "only.png", "image/png", isoOf(commentTime.getTime())));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("media-1", "media-2"), commentTime);

      assertEquals(1, result.length());
    }

    /**
     * FIXED: {@code closestUnclaimedByTime} now applies {@code
     * MAX_FALLBACK_CORRELATION_DISTANCE_MILLIS} (15 minutes) as a distance threshold — it no
     * longer force-pairs an unmatched media node with whichever unclaimed attachment is
     * nominally "closest" in time when that attachment is actually unrelated (e.g. weeks old).
     * Beyond the threshold, the fallback now yields no match rather than a wrong one.
     */
    @Test
    @DisplayName("FIXED: fallback no longer force-pairs with a closest attachment that is weeks away")
    void fallbackRespectsDistanceThreshold_noMatchWhenTooFar() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      long thirtyDaysMillis = 30L * 24 * 3600 * 1000;
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("OLD", "unrelated-old-file.pdf", "application/pdf",
              isoOf(commentTime.getTime() - thirtyDaysMillis)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("adf-media-unrelated"), commentTime);

      // Beyond the distance threshold, no fallback match is forced.
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("FIXED (tightened threshold): a same-day but hour-apart attachment is now rejected by the fallback")
    void fallbackRespectsTightenedThreshold_noMatchWhenHoursApart() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      // 1 hour away — would have matched under the old 24-hour window, but exceeds the new
      // 15-minute one. This is exactly the "busy, long-lived ticket" scenario the loose
      // threshold used to force-pair incorrectly.
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("HOUR-OLD", "unrelated-same-day-file.pdf", "application/pdf",
              isoOf(commentTime.getTime() - 3_600_000)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("adf-media-unrelated"), commentTime);

      assertEquals(0, result.length());
    }

    // -----------------------------------------------------------------------
    // Cross-comment exclusion (4-arg overload) — attachments already linked to an EARLIER
    // SupportMessage in the same conversation must not be re-claimed by a later comment's
    // fallback correlation, even when they are otherwise the closest-by-timestamp candidate.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Fallback skips an attachment already linked to a previous message, even when closest")
    void fallbackExcludesAttachmentAlreadyLinkedToEarlierMessage() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      // "ALREADY-LINKED" is the closest-by-time candidate, but a previous webhook call already
      // attached it to an earlier SupportMessage — it must be skipped in favor of "OTHER", which
      // is farther away but still within the fallback distance threshold.
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("ALREADY-LINKED", "already.png", "image/png", isoOf(commentTime.getTime() + 60_000)))
          .put(attachment("OTHER", "other.png", "image/png", isoOf(commentTime.getTime() + 300_000)));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("adf-media-unrelated"), commentTime, Set.of("ALREADY-LINKED"));

      assertEquals(1, result.length());
      assertEquals("OTHER", result.getJSONObject(0).getString("id"));
    }

    @Test
    @DisplayName("When every unclaimed candidate is already linked elsewhere, the fallback yields no match")
    void fallbackYieldsNoMatchWhenAllCandidatesAlreadyLinked() throws Exception {
      Date commentTime = new Date(1_800_000_000_000L);
      JSONArray issueAttachments = new JSONArray()
          .put(attachment("ALREADY-LINKED", "already.png", "image/png", isoOf(commentTime.getTime())));

      JSONArray result = SupportAdfAttachmentCorrelator.correlateAttachments(
          issueAttachments, List.of("adf-media-unrelated"), commentTime, Set.of("ALREADY-LINKED"));

      assertEquals(0, result.length());
    }
  }

  // -------------------------------------------------------------------------
  // resolveCommentAttachments — integration of collectAdfMediaIds + fetchIssueAttachments +
  // correlateAttachments. support.jira.token is empty in this test environment (no system
  // property override), so fetchIssueAttachments always short-circuits before any network call —
  // this is what lets these run as pure unit tests, and it also documents the graceful-degradation
  // path for a real deployment missing that token.
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveCommentAttachments")
  class ResolveCommentAttachments {

    @Test
    @DisplayName("A comment with no media nodes (plain text) returns null without any Jira call")
    void plainTextCommentReturnsNull() throws Exception {
      JSONObject body = new JSONObject().put("type", "doc").put("content", new JSONArray()
          .put(new JSONObject().put("type", "paragraph").put("content", new JSONArray()
              .put(new JSONObject().put("type", "text").put("text", "just a plain reply")))));
      JSONObject comment = new JSONObject().put("body", body);

      SupportJiraWebhookHandler.ResolvedAttachments result =
          SupportJiraWebhookHandler.resolveCommentAttachments("SUP-1", comment);

      assertNull(result.attachments);
      assertTrue(result.resolvedWikiMarkupTokens.isEmpty());
    }

    @Test
    @DisplayName("A comment with a media node but no Jira token configured resolves to null (network call cannot complete)")
    void mediaNodeWithoutTokenResolvesToNull() throws Exception {
      JSONObject media = new JSONObject().put("type", "media").put("attrs", new JSONObject().put("id", "att-1"));
      JSONObject body = new JSONObject().put("type", "doc").put("content", new JSONArray()
          .put(new JSONObject().put("type", "mediaSingle").put("content", new JSONArray().put(media))));
      JSONObject comment = new JSONObject().put("body", body);

      SupportJiraWebhookHandler.ResolvedAttachments result =
          SupportJiraWebhookHandler.resolveCommentAttachments("SUP-2", comment);

      assertNull(result.attachments);
      assertTrue(result.resolvedWikiMarkupTokens.isEmpty());
    }

    @Test
    @DisplayName("A wiki-markup image reference resolves to null when no Jira token is configured (same as ADF path)")
    void wikiMarkupWithoutTokenResolvesToNull() throws Exception {
      JSONObject comment = new JSONObject().put("body",
          "!screenshot.png|width=989,alt=\"screenshot.png\"!");

      SupportJiraWebhookHandler.ResolvedAttachments result =
          SupportJiraWebhookHandler.resolveCommentAttachments("SUP-40", comment);

      assertNull(result.attachments);
      assertTrue(result.resolvedWikiMarkupTokens.isEmpty());
    }

    @Test
    @DisplayName("A plain attachment link '[^filename.ext]' resolves to null when no Jira token is configured "
        + "(same as the embedded-image and ADF paths)")
    void plainAttachmentLinkWithoutTokenResolvesToNull() throws Exception {
      JSONObject comment = new JSONObject().put("body", "[^Hoja de cálculo sin título.xlsx]");

      SupportJiraWebhookHandler.ResolvedAttachments result =
          SupportJiraWebhookHandler.resolveCommentAttachments("SUP-41", comment);

      assertNull(result.attachments);
      assertTrue(result.resolvedWikiMarkupTokens.isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // extractWikiMarkupAttachmentRefs
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("extractWikiMarkupAttachmentRefs")
  class ExtractWikiMarkupAttachmentRefs {

    @Test
    @DisplayName("A simple image reference with no params is extracted")
    void simpleReference() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs("Look at !screenshot.png!");

      assertEquals(1, refs.size());
      assertEquals("screenshot.png", refs.get(0).filename);
      assertEquals("!screenshot.png!", refs.get(0).token);
    }

    @Test
    @DisplayName("A reference with params extracts only the filename portion, and the full token")
    void referenceWithParams() {
      String raw = "!Captura desde 2026-07-15 13-21-04.png|width=989,alt=\"Captura desde 2026-07-15 13-21-04.png\"!";
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs("before " + raw + " after");

      assertEquals(1, refs.size());
      assertEquals(raw, refs.get(0).token);
    }

    @Test
    @DisplayName("Plain text with no '!...!' patterns at all yields nothing (regression)")
    void plainTextNoPatterns() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler
          .extractWikiMarkupAttachmentRefs("Thanks for reaching out, we will look into this soon.");

      assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("'!' used as normal sentence punctuation (no dot in the matched span) is not mistaken for a file")
    void exclamationPunctuationIsNotAFile() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs("Great!works! Thanks a lot!");

      assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("Two nearby '!' punctuation marks with plain words (and spaces) in between are not mistaken "
        + "for a file, since the filename group has no dot")
    void exclamationPunctuationWithSpacesIsNotAFile() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs("Sounds good! Let's ship it! Great work!");

      assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("Null or empty text yields nothing")
    void nullOrEmpty() {
      assertTrue(SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(null).isEmpty());
      assertTrue(SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs("").isEmpty());
    }

    @Test
    @DisplayName("A plain (non-embedded) attachment link '[^filename.ext]' is extracted, spaces and accents "
        + "preserved in the filename")
    void plainAttachmentLink() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler
          .extractWikiMarkupAttachmentRefs("Va el archivo [^Hoja de cálculo sin título.xlsx] avisame");

      assertEquals(1, refs.size());
      assertEquals("Hoja de cálculo sin título.xlsx", refs.get(0).filename);
      assertEquals("[^Hoja de cálculo sin título.xlsx]", refs.get(0).token);
    }

    @Test
    @DisplayName("A message made up of ONLY a plain attachment link (no other text) is still extracted — "
        + "the shape a human agent's Jira reply takes when it carries no comment, just a file")
    void attachmentLinkOnlyMessage() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler
          .extractWikiMarkupAttachmentRefs("[^2026-04-16-slo-incident-response.md]");

      assertEquals(1, refs.size());
      assertEquals("2026-04-16-slo-incident-response.md", refs.get(0).filename);
    }

    @Test
    @DisplayName("Multiple plain attachment links in the same comment are all extracted")
    void multiplePlainAttachmentLinks() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(
          "[^Hoja de cálculo sin título.xlsx] [^Hoja de cálculo sin título.csv]");

      assertEquals(2, refs.size());
      assertEquals("Hoja de cálculo sin título.xlsx", refs.get(0).filename);
      assertEquals("Hoja de cálculo sin título.csv", refs.get(1).filename);
    }

    @Test
    @DisplayName("Embedded image ('!...!') and plain attachment link ('[^...]') tokens in the same comment "
        + "are BOTH extracted")
    void mixedEmbeddedAndPlainLinkTokens() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(
          "!screenshot.png! and also [^notes.docx]");

      assertEquals(2, refs.size());
      assertEquals("screenshot.png", refs.get(0).filename);
      assertEquals("notes.docx", refs.get(1).filename);
    }

    @Test
    @DisplayName("A bracketed reference WITHOUT the leading caret ('[filename.ext]') is not a Jira attachment "
        + "link and is not extracted — the caret is what distinguishes the syntax from plain prose brackets")
    void bracketWithoutCaretIsNotAttachmentLink() {
      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs = SupportJiraWebhookHandler
          .extractWikiMarkupAttachmentRefs("See [attached.txt] for details");

      assertTrue(refs.isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // stripResolvedWikiMarkupTokens
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("stripResolvedWikiMarkupTokens")
  class StripResolvedWikiMarkupTokens {

    @Test
    @DisplayName("No tokens to strip returns the text unchanged")
    void noTokens() {
      assertEquals("hello world",
          SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens("hello world", List.of()));
    }

    @Test
    @DisplayName("A matched token is removed and surrounding whitespace collapsed/trimmed")
    void removesToken() {
      String text = "Here you go  !screenshot.png|width=989!  thanks";
      String result = SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(text,
          List.of("!screenshot.png|width=989!"));

      assertEquals("Here you go thanks", result);
    }
  }

  // -------------------------------------------------------------------------
  // findAttachmentByFilename — the wiki-markup correlation lookup
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("findAttachmentByFilename")
  class FindAttachmentByFilename {

    @Test
    @DisplayName("Exact filename match returns the matching attachment")
    void exactMatch() throws Exception {
      JSONArray issueAttachments = new JSONArray()
          .put(new JSONObject().put("id", "10001").put("filename", "other.png"))
          .put(new JSONObject().put("id", "10002").put("filename", "Captura desde 2026-07-15 13-21-04.png"));

      JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(
          issueAttachments, "Captura desde 2026-07-15 13-21-04.png");

      assertNotNull(match);
      assertEquals("10002", match.getString("id"));
    }

    @Test
    @DisplayName("No matching filename returns null")
    void noMatch() throws Exception {
      JSONArray issueAttachments = new JSONArray()
          .put(new JSONObject().put("id", "10001").put("filename", "other.png"));

      JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(issueAttachments, "missing.png");

      assertNull(match);
    }
  }

  // -------------------------------------------------------------------------
  // Wiki-markup attachment references (embedded image '!...!' AND plain link '[^...]'): detect
  // (extractWikiMarkupAttachmentRefs) → correlate (findAttachmentByFilename) → strip
  // (stripResolvedWikiMarkupTokens) — the same composition resolveCommentAttachments/
  // parseStandardJiraWebhook wire together, exercised here as pure functions since
  // fetchIssueAttachments needs a real Jira token to reach the network (see the
  // resolveCommentAttachments section above).
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("Wiki-markup attachment references: detect, correlate, strip")
  class WikiMarkupCorrelationFlow {

    @Test
    @DisplayName("(a) A wiki-markup image reference correlates to a real attachment by filename "
        + "and the token is stripped from the displayed text")
    void correlatesAndStripsMatchedReference() throws Exception {
      String rawComment = "Here is the issue !Captura desde 2026-07-15 13-21-04.png"
          + "|width=989,alt=\"Captura desde 2026-07-15 13-21-04.png\"! let me know";
      JSONArray issueAttachments = new JSONArray()
          .put(new JSONObject().put("id", "10099").put("filename", "Captura desde 2026-07-15 13-21-04.png")
              .put("mimeType", "image/png"));

      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(rawComment);
      assertEquals(1, refs.size());

      List<String> resolvedTokens = new ArrayList<>();
      for (SupportJiraWebhookHandler.WikiMarkupImageRef ref : refs) {
        JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(issueAttachments, ref.filename);
        assertNotNull(match, "expected a matching attachment for " + ref.filename);
        assertEquals("10099", match.getString("id"));
        resolvedTokens.add(ref.token);
      }

      String finalText = SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(rawComment, resolvedTokens);

      assertEquals("Here is the issue let me know", finalText);
      assertFalse(finalText.contains("!"));
    }

    @Test
    @DisplayName("(b) A wiki-markup reference with NO matching attachment is left in the text unchanged")
    void unmatchedReferenceLeftAsIs() throws Exception {
      String rawComment = "Here is the issue !unknown-file.png|width=989! let me know";
      JSONArray issueAttachments = new JSONArray()
          .put(new JSONObject().put("id", "10099").put("filename", "other.png"));

      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(rawComment);
      assertEquals(1, refs.size());

      List<String> resolvedTokens = new ArrayList<>();
      for (SupportJiraWebhookHandler.WikiMarkupImageRef ref : refs) {
        JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(issueAttachments, ref.filename);
        if (match != null) resolvedTokens.add(ref.token);
      }
      assertTrue(resolvedTokens.isEmpty());

      String finalText = SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(rawComment, resolvedTokens);

      // No token was resolved, so stripResolvedWikiMarkupTokens must be a no-op.
      assertEquals(rawComment, finalText);
    }

    @Test
    @DisplayName("(c) Plain text with no '!...!' patterns at all is completely unaffected (regression)")
    void plainTextUnaffected() {
      String rawComment = "Thanks for the report, we're looking into it now.";

      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(rawComment);

      assertTrue(refs.isEmpty());
      assertEquals(rawComment, SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(rawComment, List.of()));
    }

    @Test
    @DisplayName("(d) A plain attachment link '[^filename.ext]' correlates to a real attachment by filename "
        + "and the token is stripped from the displayed text, for every document type the chat supports")
    void correlatesAndStripsPlainAttachmentLinkForEverySupportedType() throws Exception {
      // Mirrors ConversationView.jsx's ALLOWED_DOC_EXTENSIONS on the frontend (schema_forge):
      // pdf, csv, txt, xlsx, docx, md.
      String[][] cases = {
          {"reporte.pdf", "application/pdf"},
          {"Hoja de cálculo sin título.csv", "text/csv"},
          {"notas.txt", "text/plain"},
          {"Hoja de cálculo sin título.xlsx",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
          {"documento.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
          {"2026-04-16-slo-incident-response.md", "text/markdown"},
      };

      for (String[] testCase : cases) {
        String filename = testCase[0];
        String mimeType = testCase[1];
        String rawComment = "Te paso el archivo [^" + filename + "] cualquier cosa me avisas";
        JSONArray issueAttachments = new JSONArray()
            .put(new JSONObject().put("id", "20001").put("filename", filename).put("mimeType", mimeType));

        List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
            SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(rawComment);
        assertEquals(1, refs.size(), "expected exactly one ref for " + filename);

        JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(issueAttachments, refs.get(0).filename);
        assertNotNull(match, "expected a matching attachment for " + filename);
        JSONObject meta = SupportAdfAttachmentCorrelator.toAttachmentMeta(match);
        assertEquals("20001", meta.getString("id"));
        assertEquals(filename, meta.getString("filename"));
        assertEquals(mimeType, meta.getString("mimeType"));

        String finalText = SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(
            rawComment, List.of(refs.get(0).token));
        assertEquals("Te paso el archivo cualquier cosa me avisas", finalText,
            "token for " + filename + " should be stripped from the displayed text");
      }
    }

    @Test
    @DisplayName("(e) A message made up of ONLY a plain attachment link strips down to empty text — the "
        + "attachment then renders solely via message.attachments on the frontend (attachment-only message)")
    void attachmentOnlyMessageStripsToEmptyText() throws Exception {
      String rawComment = "[^2026-04-16-slo-incident-response.md]";
      JSONArray issueAttachments = new JSONArray()
          .put(new JSONObject().put("id", "20002").put("filename", "2026-04-16-slo-incident-response.md")
              .put("mimeType", "text/markdown"));

      List<SupportJiraWebhookHandler.WikiMarkupImageRef> refs =
          SupportJiraWebhookHandler.extractWikiMarkupAttachmentRefs(rawComment);
      JSONObject match = SupportJiraWebhookHandler.findAttachmentByFilename(issueAttachments, refs.get(0).filename);
      assertNotNull(match);

      String finalText = SupportJiraWebhookHandler.stripResolvedWikiMarkupTokens(
          rawComment, List.of(refs.get(0).token));

      assertEquals("", finalText);
    }
  }

  // -------------------------------------------------------------------------
  // findAlreadyLinkedAttachmentIds — cross-comment exclusion source
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("findAlreadyLinkedAttachmentIds")
  class FindAlreadyLinkedAttachmentIds {

    @Test
    @DisplayName("No matching conversation returns an empty set")
    void noConversation() {
      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList());

        Set<String> ids = SupportAdfAttachmentCorrelator.findAlreadyLinkedAttachmentIds("SUP-30");

        assertTrue(ids.isEmpty());
      }
    }

    @Test
    @DisplayName("Collects attachment ids across multiple earlier messages, skipping malformed JSON")
    void collectsIdsAcrossMessages() {
      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, List.of(mockConversation("conv-1")));

        SupportMessage msg1 = mock(SupportMessage.class);
        when(msg1.getAttachments()).thenReturn("[{\"id\":\"att-1\"},{\"id\":\"att-2\"}]");
        SupportMessage msg2 = mock(SupportMessage.class);
        when(msg2.getAttachments()).thenReturn("not valid json");
        SupportMessage msg3 = mock(SupportMessage.class);
        when(msg3.getAttachments()).thenReturn("[{\"id\":\"att-3\"}]");
        mockCriteria(obDal, SupportMessage.class, List.of(msg1, msg2, msg3));

        Set<String> ids = SupportAdfAttachmentCorrelator.findAlreadyLinkedAttachmentIds("SUP-31");

        assertEquals(Set.of("att-1", "att-2", "att-3"), ids);
      }
    }
  }

  // -------------------------------------------------------------------------
  // handle — top-level dispatcher
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handle")
  class Handle {

    @Test
    @DisplayName("Empty body with no issueKey query param is ignored (falls through to automation parsing)")
    void emptyBodyFallsThroughToAutomation() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      HttpServletRequest request = mockRequestWithBody("");

      SupportJiraWebhookHandler.handle(request, response);

      assertEquals("{\"status\":\"ignored_no_key\"}", capture.toString());
    }

    @Test
    @DisplayName("Standard webhook comment with blank ADF text is stored as empty_body")
    void standardWebhookEmptyBody() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject comment = new JSONObject()
          .put("id", "c1").put("jsdPublic", true).put("body", "");
      JSONObject json = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-20"))
          .put("comment", comment);
      HttpServletRequest request = mockRequestWithBody(json.toString());

      SupportJiraWebhookHandler.handle(request, response);

      assertTrue(capture.toString().contains("empty_body"));
    }

    @Test
    @DisplayName("Standard webhook comment with real text is persisted into the matching conversation")
    void standardWebhookStoresComment() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject author = new JSONObject().put("emailAddress", "agent@example.com").put("displayName", "Agent Smith");
      JSONObject comment = new JSONObject()
          .put("id", "c2").put("jsdPublic", true).put("author", author).put("body", "Thanks for reaching out");
      JSONObject json = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-21"))
          .put("comment", comment);
      HttpServletRequest request = mockRequestWithBody(json.toString());

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-77");
        mockCriteria(obDal, SupportConversation.class, List.of(conv));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList()); // no duplicate external_id
        when(obDal.get(SupportConversation.class, "conv-77")).thenReturn(conv);
        when(obDal.get(User.class, SupportConversationsServlet.SYSTEM_USER_ID)).thenReturn(mock(User.class));

        OBProvider provider = mock(OBProvider.class);
        providerMock.when(OBProvider::getInstance).thenReturn(provider);
        when(provider.get(SupportMessage.class)).thenReturn(mock(SupportMessage.class));

        SupportJiraWebhookHandler.handle(request, response);
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
      assertTrue(capture.toString().contains("conv-77"));
    }

    @Test
    @DisplayName("An attachment-only comment (empty text, resolved attachments) is NOT dropped as "
        + "empty_body — this is the real shape of a Jira reply that carries only a file, e.g. "
        + "'[^report.pdf]' resolved and stripped down to '' (regression: the empty-text guard used "
        + "to fire on text alone, discarding the attachment along with it)")
    void storeJiraWebhookCommentPersistsAttachmentOnlyMessage() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONArray attachments = new JSONArray()
          .put(new JSONObject().put("id", "30001").put("filename", "report.pdf").put("mimeType", "application/pdf"));
      SupportJiraWebhookHandler.JiraWebhookComment comment = new SupportJiraWebhookHandler.JiraWebhookComment(
          "SUP-23", "c4", "Agent Smith", "agent@example.com", "", "", attachments);

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-88");
        mockCriteria(obDal, SupportConversation.class, List.of(conv));
        mockCriteria(obDal, SupportMessage.class, Collections.emptyList()); // no duplicate external_id
        when(obDal.get(SupportConversation.class, "conv-88")).thenReturn(conv);
        when(obDal.get(User.class, SupportConversationsServlet.SYSTEM_USER_ID)).thenReturn(mock(User.class));

        OBProvider provider = mock(OBProvider.class);
        providerMock.when(OBProvider::getInstance).thenReturn(provider);
        SupportMessage msg = mock(SupportMessage.class);
        when(provider.get(SupportMessage.class)).thenReturn(msg);

        SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);

        verify(msg).setAttachments(attachments.toString());
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
      assertTrue(capture.toString().contains("conv-88"));
      assertFalse(capture.toString().contains("empty_body"));
    }

    @Test
    @DisplayName("Standard webhook with no conversation match reports no_conversation")
    void standardWebhookNoConversation() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      JSONObject comment = new JSONObject()
          .put("id", "c3").put("jsdPublic", true).put("body", "Hello there");
      JSONObject json = new JSONObject()
          .put("issue", new JSONObject().put("key", "SUP-22"))
          .put("comment", comment);
      HttpServletRequest request = mockRequestWithBody(json.toString());

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mockObDal(dalMock);
        mockCriteria(obDal, SupportConversation.class, Collections.emptyList());

        SupportJiraWebhookHandler.handle(request, response);
      }

      assertTrue(capture.toString().contains("no_conversation"));
    }
  }

  // -------------------------------------------------------------------------
  // storeJiraWebhookComment — bot-echo detection by display name (JIRA_BOT_NAME) and
  // reporter-reply sender attribution (isReporterReply). Called directly since the method is
  // package-private, avoiding the need to build a full JSON/query-param webhook request just to
  // reach it.
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("storeJiraWebhookComment — bot detection by name & reporter sender attribution")
  class StoreJiraWebhookCommentDirect {

    private SupportJiraWebhookHandler.JiraWebhookComment comment(String jiraKey, String commentId,
        String authorName, String authorEmail, String authorAccountId, String text) {
      return new SupportJiraWebhookHandler.JiraWebhookComment(jiraKey, commentId, authorName, authorEmail,
          authorAccountId, text, null);
    }

    /** Stubs the DB round-trip {@code storeJiraWebhookComment} needs to actually persist a
     * message: a matching conversation, no existing message for the external id (dedupe check),
     * the system user lookup, and a fresh {@code SupportMessage} mock handed back by
     * {@code OBProvider}. Returns that message mock so callers can verify what was set on it. */
    private SupportMessage stubPersistence(OBDal obDal, MockedStatic<OBProvider> providerMock,
        SupportConversation conv) {
      mockCriteria(obDal, SupportConversation.class, List.of(conv));
      mockCriteria(obDal, SupportMessage.class, Collections.emptyList());
      when(obDal.get(User.class, SupportConversationsServlet.SYSTEM_USER_ID)).thenReturn(mock(User.class));
      // updateConvSummary (called after a successful insert) re-fetches the conversation by id.
      when(obDal.get(SupportConversation.class, conv.getId())).thenReturn(conv);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      SupportMessage msg = mock(SupportMessage.class);
      when(provider.get(SupportMessage.class)).thenReturn(msg);
      return msg;
    }

    @Test
    @DisplayName("Bot comment recognized by display name when the author email is empty is skipped as "
        + "bot echo (JIRA_BOT_NAME defaults to 'Information Etendo'; JIRA_BOT_EMAIL defaults to empty in "
        + "this test environment, so email alone could never have caught this — the real production bug "
        + "this fixes)")
    void botRecognizedByNameWithNoEmail() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      SupportJiraWebhookHandler.JiraWebhookComment comment =
          comment("SUP-60", "c60", "Information Etendo", "", "acc-bot", "Echo of our own comment");

      SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);

      assertEquals("{\"status\":\"skipped_bot\"}", capture.toString());
    }

    @Test
    @DisplayName("A comment whose author name differs from the bot name (and whose email does not match "
        + "either) is NOT treated as a bot echo and is persisted normally")
    void neitherNameNorEmailMatchIsNotBot() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      SupportJiraWebhookHandler.JiraWebhookComment comment =
          comment("SUP-61", "c61", "Agent Smith", "agent@example.com", "acc-agent", "Real human reply");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        stubPersistence(obDal, providerMock, mockConversation("conv-61"));

        SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);
      }

      assertFalse(capture.toString().contains("skipped_bot"));
      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("Reporter's own accountId matching the conversation's stored reporter accountId results "
        + "in the message being inserted with sender=user — AND it is genuinely persisted (not skipped): "
        + "an earlier version of this logic incorrectly filtered reporter replies out entirely; this is "
        + "the regression test proving that no longer happens")
    void reporterReplyIsPersistedWithUserSender() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      SupportJiraWebhookHandler.JiraWebhookComment comment =
          comment("SUP-62", "c62", "Jane Reporter", "jane@example.com", "reporter-acc-1", "Replying via email");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-62");
        when(conv.getJiraReporterAccountId()).thenReturn("reporter-acc-1");
        SupportMessage msg = stubPersistence(obDal, providerMock, conv);

        SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);

        verify(msg).setSender("user");
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
      assertTrue(capture.toString().contains("conv-62"));
    }

    @Test
    @DisplayName("accountId present but different from the conversation's reporter accountId: a genuinely "
        + "different human agent, sender=human")
    void nonMatchingAccountIdIsHumanSender() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      SupportJiraWebhookHandler.JiraWebhookComment comment =
          comment("SUP-63", "c63", "Agent Smith", "agent@example.com", "acc-agent", "Support reply");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-63");
        when(conv.getJiraReporterAccountId()).thenReturn("reporter-acc-1");
        SupportMessage msg = stubPersistence(obDal, providerMock, conv);

        SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);

        verify(msg).setSender("human");
      }
    }

    @Test
    @DisplayName("Empty accountId (e.g. the Automation query-param path) defaults to sender=human without "
        + "throwing, even when the conversation's own reporter accountId is null (isReporterReply's "
        + "equals() is called on the non-null comment side, so this is null-safe)")
    void emptyAccountIdIsHumanSenderNoNpe() throws Exception {
      StringWriter capture = new StringWriter();
      HttpServletResponse response = mockResponse(capture);
      SupportJiraWebhookHandler.JiraWebhookComment comment =
          comment("SUP-64", "c64", "Agent Smith", "agent@example.com", "", "Support reply via automation");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
        OBDal obDal = mockObDal(dalMock);
        SupportConversation conv = mockConversation("conv-64");
        // conv.getJiraReporterAccountId() defaults to null (not stubbed) — this is the case the
        // null-safety matters for.
        SupportMessage msg = stubPersistence(obDal, providerMock, conv);

        SupportJiraWebhookHandler.storeJiraWebhookComment(response, comment);

        verify(msg).setSender("human");
      }

      assertTrue(capture.toString().contains("\"status\":\"ok\""));
    }
  }
}
