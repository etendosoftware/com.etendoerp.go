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
import java.util.Collections;
import java.util.List;

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
    @DisplayName("Email matching the configured Jira username (default env) returns true, case-insensitively")
    void matchesJiraUsername() {
      // support.jira.username defaults to info@smfconsulting.es in this test environment.
      assertTrue(SupportJiraWebhookHandler.isBotEmail("INFO@SMFCONSULTING.ES"));
      assertTrue(SupportJiraWebhookHandler.isBotEmail("info@smfconsulting.es"));
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
}
