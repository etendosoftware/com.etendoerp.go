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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

/**
 * Tests for {@link SupportIntegrationClient}.
 */
class SupportIntegrationClientTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<SupportIntegrationClient> constructor = SupportIntegrationClient.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  // -------------------------------------------------------------------------
  // appendAttachmentParts / appendSingleAttachmentPart
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("appendAttachmentParts / appendSingleAttachmentPart")
  class AttachmentParts {

    @Test
    @DisplayName("Null attachments array leaves parts untouched")
    void nullAttachments() throws Exception {
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendAttachmentParts(parts, null);
      assertEquals(0, parts.length());
    }

    @Test
    @DisplayName("Empty attachments array leaves parts untouched")
    void emptyAttachments() throws Exception {
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendAttachmentParts(parts, new JSONArray());
      assertEquals(0, parts.length());
    }

    @Test
    @DisplayName("Null element in attachments array is skipped")
    void nullElementSkipped() throws Exception {
      JSONArray attachments = new JSONArray();
      attachments.put(JSONObject.NULL);
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendAttachmentParts(parts, attachments);
      assertEquals(0, parts.length());
    }

    @Test
    @DisplayName("Attachment with text content appends a text part")
    void textAttachment() throws Exception {
      JSONObject att = new JSONObject().put("name", "notes.txt").put("text", "hello world");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("notes.txt"));
      assertTrue(text.contains("hello world"));
    }

    @Test
    @DisplayName("Attachment with data + image mimeType appends inlineData part")
    void imageAttachment() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "photo.png").put("mimeType", "image/png").put("data", "QUJD");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      JSONObject inlineData = parts.getJSONObject(0).getJSONObject("inlineData");
      assertEquals("image/png", inlineData.getString("mimeType"));
      assertEquals("QUJD", inlineData.getString("data"));
    }

    @Test
    @DisplayName("Attachment with data + application/pdf mimeType appends inlineData part")
    void pdfAttachment() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "doc.pdf").put("mimeType", "application/pdf").put("data", "JVBERi0=");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      JSONObject inlineData = parts.getJSONObject(0).getJSONObject("inlineData");
      assertEquals("application/pdf", inlineData.getString("mimeType"));
    }

    @Test
    @DisplayName("Attachment with data + non-image/pdf mimeType appends a text placeholder")
    void otherMimeTypeAttachment() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "data.csv").put("mimeType", "text/csv").put("data", "YSxiLGM=");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("data.csv"));
    }

    @Test
    @DisplayName("Attachment with data + DOCX mimeType appends inlineData part (not a placeholder)")
    void docxAttachment() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "report.docx")
          .put("mimeType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
          .put("data", "UEsDBA==");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      JSONObject inlineData = parts.getJSONObject(0).getJSONObject("inlineData");
      assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          inlineData.getString("mimeType"));
      assertEquals("UEsDBA==", inlineData.getString("data"));
    }

    @Test
    @DisplayName("Attachment with data + XLSX mimeType appends inlineData part (not a placeholder)")
    void xlsxAttachment() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "sheet.xlsx")
          .put("mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
          .put("data", "UEsDBB0=");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      JSONObject inlineData = parts.getJSONObject(0).getJSONObject("inlineData");
      assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          inlineData.getString("mimeType"));
      assertEquals("UEsDBB0=", inlineData.getString("data"));
    }

    @Test
    @DisplayName("Text attachment with both text and data appends a text part AND an inlineData part")
    void textAttachmentWithDataAppendsBothParts() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "data.csv")
          .put("mimeType", "text/csv")
          .put("text", "a,b,c")
          .put("data", "YSxiLGM=");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(2, parts.length());
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("data.csv"));
      assertTrue(text.contains("a,b,c"));
      JSONObject inlineData = parts.getJSONObject(1).getJSONObject("inlineData");
      assertEquals("text/csv", inlineData.getString("mimeType"));
      assertEquals("YSxiLGM=", inlineData.getString("data"));
    }

    @Test
    @DisplayName("Text attachment with only text (no data, older/cached frontend) still appends a single text part")
    void textAttachmentWithoutDataAppendsOnlyTextPart() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "notes.txt")
          .put("mimeType", "text/plain")
          .put("text", "hello world");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      assertFalse(parts.getJSONObject(0).has("inlineData"));
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("notes.txt"));
      assertTrue(text.contains("hello world"));
    }

    @Test
    @DisplayName("Attachment with audio mimeType is not inlined — falls back to text placeholder")
    void audioMimeTypeStaysUnsupported() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "voice.webm").put("mimeType", "audio/webm").put("data", "T2dnUw==");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      assertFalse(parts.getJSONObject(0).has("inlineData"));
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("voice.webm"));
    }

    @Test
    @DisplayName("Attachment with video mimeType is not inlined — falls back to text placeholder")
    void videoMimeTypeStaysUnsupported() throws Exception {
      JSONObject att = new JSONObject()
          .put("name", "clip.mp4").put("mimeType", "video/mp4").put("data", "AAAAGGZ0eXA=");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(1, parts.length());
      assertFalse(parts.getJSONObject(0).has("inlineData"));
      String text = parts.getJSONObject(0).getString("text");
      assertTrue(text.contains("clip.mp4"));
    }

    @Test
    @DisplayName("Attachment with neither text nor data is skipped")
    void emptyAttachmentSkipped() throws Exception {
      JSONObject att = new JSONObject().put("name", "empty.bin").put("mimeType", "application/octet-stream");
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendSingleAttachmentPart(parts, att);
      assertEquals(0, parts.length());
    }

    @Test
    @DisplayName("Multiple attachments in one array are all appended in order")
    void multipleAttachments() throws Exception {
      JSONArray attachments = new JSONArray();
      attachments.put(new JSONObject().put("name", "a.txt").put("text", "AAA"));
      attachments.put(new JSONObject().put("name", "b.png").put("mimeType", "image/png").put("data", "QkJC"));
      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendAttachmentParts(parts, attachments);
      assertEquals(2, parts.length());
    }

    @Test
    @DisplayName("Mixed image + DOCX + text-with-data attachments produce parts in the correct order")
    void mixedAttachmentTypesInOrder() throws Exception {
      JSONArray attachments = new JSONArray();
      attachments.put(new JSONObject()
          .put("name", "photo.png").put("mimeType", "image/png").put("data", "QUJD"));
      attachments.put(new JSONObject()
          .put("name", "report.docx")
          .put("mimeType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
          .put("data", "UEsDBA=="));
      attachments.put(new JSONObject()
          .put("name", "data.csv").put("mimeType", "text/csv")
          .put("text", "a,b,c").put("data", "YSxiLGM="));

      JSONArray parts = new JSONArray();
      SupportIntegrationClient.appendAttachmentParts(parts, attachments);

      // image -> 1 inlineData part; docx -> 1 inlineData part; text-with-data -> text part + inlineData part
      assertEquals(4, parts.length());

      JSONObject imagePart = parts.getJSONObject(0).getJSONObject("inlineData");
      assertEquals("image/png", imagePart.getString("mimeType"));

      JSONObject docxPart = parts.getJSONObject(1).getJSONObject("inlineData");
      assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          docxPart.getString("mimeType"));

      String csvText = parts.getJSONObject(2).getString("text");
      assertTrue(csvText.contains("data.csv"));
      assertTrue(csvText.contains("a,b,c"));

      JSONObject csvInlineData = parts.getJSONObject(3).getJSONObject("inlineData");
      assertEquals("text/csv", csvInlineData.getString("mimeType"));
      assertEquals("YSxiLGM=", csvInlineData.getString("data"));
    }
  }

  // -------------------------------------------------------------------------
  // parseAdkResponse / appendEventText
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseAdkResponse / appendEventText")
  class ParseAdkResponse {

    @Test
    @DisplayName("Valid model events concatenate their text parts")
    void concatenatesModelText() {
      String json = "[" +
          "{\"author\":\"agent\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello \"},{\"text\":\"there\"}]}}" +
          "]";
      assertEquals("Hello there", SupportIntegrationClient.parseAdkResponse(json));
    }

    @Test
    @DisplayName("Events authored by a triage agent are skipped")
    void skipsTriageAuthor() {
      String json = "[" +
          "{\"author\":\"triage_agent\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ignored\"}]}}," +
          "{\"author\":\"support_agent\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"kept\"}]}}" +
          "]";
      assertEquals("kept", SupportIntegrationClient.parseAdkResponse(json));
    }

    @Test
    @DisplayName("Events with content.role != model are skipped")
    void skipsNonModelRole() {
      String json = "[" +
          "{\"author\":\"user\",\"content\":{\"role\":\"user\",\"parts\":[{\"text\":\"ignored\"}]}}" +
          "]";
      assertNull(SupportIntegrationClient.parseAdkResponse(json));
    }

    @Test
    @DisplayName("Events with no content key are skipped without error")
    void skipsMissingContent() {
      String json = "[{\"author\":\"agent\"}]";
      assertNull(SupportIntegrationClient.parseAdkResponse(json));
    }

    @Test
    @DisplayName("Malformed JSON returns null")
    void malformedJsonReturnsNull() {
      assertNull(SupportIntegrationClient.parseAdkResponse("not json at all"));
    }

    @Test
    @DisplayName("Empty array returns null")
    void emptyArrayReturnsNull() {
      assertNull(SupportIntegrationClient.parseAdkResponse("[]"));
    }

    @Test
    @DisplayName("appendEventText ignores a null event")
    void appendEventTextNullEvent() {
      StringBuilder sb = new StringBuilder();
      SupportIntegrationClient.appendEventText(sb, null);
      assertEquals(0, sb.length());
    }

    @Test
    @DisplayName("appendEventText ignores an event without a content field")
    void appendEventTextNoContent() throws Exception {
      StringBuilder sb = new StringBuilder();
      SupportIntegrationClient.appendEventText(sb, new JSONObject().put("author", "agent"));
      assertEquals(0, sb.length());
    }

    @Test
    @DisplayName("appendEventText ignores parts with blank text")
    void appendEventTextBlankPart() throws Exception {
      JSONObject event = new JSONObject(
          "{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\"},{\"text\":\"real\"}]}}");
      StringBuilder sb = new StringBuilder();
      SupportIntegrationClient.appendEventText(sb, event);
      assertEquals("real", sb.toString());
    }
  }

  // -------------------------------------------------------------------------
  // buildFeedbackComment
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildFeedbackComment")
  class BuildFeedbackComment {

    @Test
    @DisplayName("Without a comment, only the score line is present")
    void scoreOnly() {
      String result = SupportIntegrationClient.buildFeedbackComment(4, null);
      assertEquals("⭐ Valoración de satisfacción: 4/5", result);
    }

    @Test
    @DisplayName("Empty comment behaves like no comment")
    void emptyComment() {
      String result = SupportIntegrationClient.buildFeedbackComment(3, "");
      assertEquals("⭐ Valoración de satisfacción: 3/5", result);
    }

    @Test
    @DisplayName("With a comment, the customer comment is appended")
    void withComment() {
      String result = SupportIntegrationClient.buildFeedbackComment(5, "Great support!");
      assertTrue(result.startsWith("⭐ Valoración de satisfacción: 5/5"));
      assertTrue(result.contains("Comentario del cliente: Great support!"));
    }
  }

  // -------------------------------------------------------------------------
  // getUserEmail
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("getUserEmail")
  class GetUserEmail {

    private MockedStatic<OBDal> mockObDal(User user) {
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(eq(User.class), anyString())).thenReturn(user);
      MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      return dalMock;
    }

    @Test
    @DisplayName("Returns the email column when populated")
    void returnsEmailWhenPresent() {
      User user = mock(User.class);
      when(user.getEmail()).thenReturn("real@example.com");
      try (MockedStatic<OBDal> dalMock = mockObDal(user)) {
        assertEquals("real@example.com", SupportIntegrationClient.getUserEmail("100"));
      }
    }

    @Test
    @DisplayName("Falls back to username when it looks like an email")
    void fallsBackToUsernameWhenEmailLike() {
      User user = mock(User.class);
      when(user.getEmail()).thenReturn("");
      when(user.getUsername()).thenReturn("goportal@example.com");
      try (MockedStatic<OBDal> dalMock = mockObDal(user)) {
        assertEquals("goportal@example.com", SupportIntegrationClient.getUserEmail("101"));
      }
    }

    @Test
    @DisplayName("Returns null when neither email nor an email-like username is present")
    void returnsNullWhenNeitherLooksLikeEmail() {
      User user = mock(User.class);
      when(user.getEmail()).thenReturn(null);
      when(user.getUsername()).thenReturn("admin");
      try (MockedStatic<OBDal> dalMock = mockObDal(user)) {
        assertNull(SupportIntegrationClient.getUserEmail("0"));
      }
    }

    @Test
    @DisplayName("Returns null when the user is not found")
    void returnsNullWhenUserNotFound() {
      try (MockedStatic<OBDal> dalMock = mockObDal(null)) {
        assertNull(SupportIntegrationClient.getUserEmail("999"));
      }
    }
  }

  // -------------------------------------------------------------------------
  // HTTP-backed methods — no server is guaranteed to be listening at the
  // configured localhost URLs in CI, so these tests only assert the
  // try/catch wrapping the network call never lets an exception escape
  // (this still exercises the JSON/request-building code ahead of the
  // network call). See class-level notes in the test report for what is
  // intentionally NOT covered here.
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("HTTP-backed methods do not throw")
  class HttpBackedMethods {

    @Test
    @DisplayName("createAdkSession swallows failures without throwing")
    void createAdkSessionDoesNotThrow() {
      assertDoesNotThrow(() ->
          SupportIntegrationClient.createAdkSession("user1", "session1", "es", "user@example.com"));
    }

    @Test
    @DisplayName("createAdkSession with null email still builds a valid request")
    void createAdkSessionNullEmailDoesNotThrow() {
      assertDoesNotThrow(() ->
          SupportIntegrationClient.createAdkSession("user1", "session1", "es", null));
    }

    @Test
    @DisplayName("sendToAdk (no attachments, no stateDelta) swallows failures without throwing")
    void sendToAdkSimpleDoesNotThrow() {
      assertDoesNotThrow(() ->
          SupportIntegrationClient.sendToAdk("user1", "session1", "hello", null));
    }

    @Test
    @DisplayName("sendToAdk with attachments and stateDelta swallows failures without throwing")
    void sendToAdkFullDoesNotThrow() throws Exception {
      JSONArray attachments = new JSONArray();
      attachments.put(new JSONObject().put("name", "a.txt").put("text", "hi"));
      JSONObject stateDelta = new JSONObject().put("human_takeover", false);
      assertDoesNotThrow(() ->
          SupportIntegrationClient.sendToAdk("user1", "session1", "hello", attachments, stateDelta));
    }

    @Test
    @DisplayName("postJiraComment is a no-op when jiraKey is null or empty")
    void postJiraCommentNoopOnMissingKey() {
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment(null, "text", false));
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("", "text", false));
    }

    @Test
    @DisplayName("postJiraComment is a no-op when the API token is not configured (default test env)")
    void postJiraCommentNoopWhenNoToken() {
      // support.jira.token defaults to "" in this test environment, so the early
      // guard always short-circuits before any HTTP call is attempted.
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("SUP-1", "some message", false));
    }

    /**
     * NOT independently assertable in this suite: whether the built JSON payload actually
     * contains {@code "internal":true} vs {@code "internal":false} for the two call sites
     * ({@link SupportConversationsServlet}'s human-takeover forward passes {@code false}; its
     * CSAT feedback branch passes {@code true}). {@code JIRA_API_TOKEN} is a {@code static final}
     * read once from {@code support.jira.token} at class-load time (empty by default, and no
     * test in this suite overrides it), so {@code postJiraComment} always returns before the
     * payload string is even built — there is no seam to intercept it without either (a) a real
     * token configured before this class first loads in the test JVM, which no per-test
     * mechanism controls here, or (b) extracting payload construction into a separately testable
     * pure function, a production change out of scope for a coverage-only pass. This test only
     * documents that both boolean values are accepted without throwing; see the test-run report
     * for this gap flagged explicitly.
     */
    @Test
    @DisplayName("postJiraComment no-ops safely for both internal=true and internal=false (payload-content "
        + "assertion not feasible here — see Javadoc)")
    void postJiraCommentNoopForBothInternalValues() {
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("SUP-1", "message", true));
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("SUP-1", "message", false));
    }

    @Test
    @DisplayName("postJiraCsatLabel is a no-op when jiraKey is null or empty")
    void postJiraCsatLabelNoopOnMissingKey() {
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraCsatLabel(null, 5));
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraCsatLabel("", 5));
    }

    @Test
    @DisplayName("postJiraCsatLabel is a no-op when the API token is not configured (default test env)")
    void postJiraCsatLabelNoopWhenNoToken() {
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraCsatLabel("SUP-1", 5));
    }
  }
}
