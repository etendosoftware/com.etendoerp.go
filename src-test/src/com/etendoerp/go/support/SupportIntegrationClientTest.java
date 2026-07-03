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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SupportIntegrationClient}.
 */
class SupportIntegrationClientTest {

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

    @Test
    @DisplayName("Returns the email column when populated")
    void returnsEmailWhenPresent() throws SQLException {
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString("email")).thenReturn("real@example.com");

      String email = SupportIntegrationClient.getUserEmail(conn, "100");
      assertEquals("real@example.com", email);
    }

    @Test
    @DisplayName("Falls back to username when it looks like an email")
    void fallsBackToUsernameWhenEmailLike() throws SQLException {
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString("email")).thenReturn("");
      when(rs.getString("username")).thenReturn("goportal@example.com");

      String email = SupportIntegrationClient.getUserEmail(conn, "101");
      assertEquals("goportal@example.com", email);
    }

    @Test
    @DisplayName("Returns null when neither email nor an email-like username is present")
    void returnsNullWhenNeitherLooksLikeEmail() throws SQLException {
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString("email")).thenReturn(null);
      when(rs.getString("username")).thenReturn("admin");

      assertNull(SupportIntegrationClient.getUserEmail(conn, "0"));
    }

    @Test
    @DisplayName("Returns null when no row is found")
    void returnsNullWhenNoRow() throws SQLException {
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      assertNull(SupportIntegrationClient.getUserEmail(conn, "999"));
    }

    @Test
    @DisplayName("SQLException is caught and null is returned")
    void sqlExceptionReturnsNull() throws SQLException {
      Connection conn = mock(Connection.class);
      when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
          .thenThrow(new SQLException("boom"));

      assertNull(SupportIntegrationClient.getUserEmail(conn, "1"));
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
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment(null, "text"));
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("", "text"));
    }

    @Test
    @DisplayName("postJiraComment is a no-op when the API token is not configured (default test env)")
    void postJiraCommentNoopWhenNoToken() {
      // support.jira.token defaults to "" in this test environment, so the early
      // guard always short-circuits before any HTTP call is attempted.
      assertDoesNotThrow(() -> SupportIntegrationClient.postJiraComment("SUP-1", "some message"));
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
