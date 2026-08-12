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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link EmailMessageEdits}, the allowlisted {@code messageEdits} command field of
 * the document-send family (ETP-4717). Mirrors {@link EmailRecipientEditsTest}.
 */
public class EmailMessageEditsTest {

  @Test
  public void absentFieldYieldsEmptyOptional() throws Exception {
    assertFalse(EmailMessageEdits.fromBody(new JSONObject("{\"recordId\":\"r1\"}")).isPresent());
    assertFalse(EmailMessageEdits.fromBody(null).isPresent());
  }

  @Test
  public void parsesSubjectAndMessageFromCommandBody() throws Exception {
    JSONObject body = new JSONObject(
        "{\"messageEdits\":{\"subject\":\"Su pedido\",\"message\":\"Texto libre\"}}");
    EmailMessageEdits edits = EmailMessageEdits.fromBody(body).get();
    assertEquals("Su pedido", edits.getSubject());
    assertEquals("Texto libre", edits.getMessage());
  }

  @Test
  public void acceptsMessageOnlyAndSubjectOnly() throws Exception {
    EmailMessageEdits messageOnly = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"message\":\"solo cuerpo\"}}")).get();
    assertNull(messageOnly.getSubject());
    assertEquals("solo cuerpo", messageOnly.getMessage());

    EmailMessageEdits subjectOnly = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"subject\":\"solo asunto\"}}")).get();
    assertEquals("solo asunto", subjectOnly.getSubject());
    assertNull(subjectOnly.getMessage());
  }

  @Test
  public void rejectsNonObjectPayload() throws Exception {
    try {
      EmailMessageEdits.fromBody(new JSONObject("{\"messageEdits\":\"texto\"}"));
      fail("expected rejection");
    } catch (EmailMessageEdits.InvalidMessageEditsException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }

  @Test
  public void rejectsUnknownField() throws Exception {
    try {
      EmailMessageEdits.fromBody(
          new JSONObject("{\"messageEdits\":{\"bodyHtml\":\"<b>no</b>\"}}"));
      fail("expected rejection");
    } catch (EmailMessageEdits.InvalidMessageEditsException expected) {
      assertTrue(expected.getMessage().contains("bodyHtml"));
    }
  }

  @Test
  public void rejectsPayloadWithNeitherSubjectNorMessage() throws Exception {
    try {
      EmailMessageEdits.fromBody(
          new JSONObject("{\"messageEdits\":{\"subject\":\" \",\"message\":\"  \"}}"));
      fail("expected rejection");
    } catch (EmailMessageEdits.InvalidMessageEditsException expected) {
      // expected
    }
  }

  @Test
  public void rejectsOversizedSubjectAndMessage() throws Exception {
    JSONObject longSubject = new JSONObject();
    longSubject.put("subject", repeat('s', EmailMessageEdits.MAX_SUBJECT_LENGTH + 1));
    JSONObject subjectBody = new JSONObject();
    subjectBody.put("messageEdits", longSubject);
    try {
      EmailMessageEdits.fromBody(subjectBody);
      fail("expected subject rejection");
    } catch (EmailMessageEdits.InvalidMessageEditsException expected) {
      // expected
    }

    JSONObject longMessage = new JSONObject();
    longMessage.put("message", repeat('m', EmailMessageEdits.MAX_MESSAGE_LENGTH + 1));
    JSONObject messageBody = new JSONObject();
    messageBody.put("messageEdits", longMessage);
    try {
      EmailMessageEdits.fromBody(messageBody);
      fail("expected message rejection");
    } catch (EmailMessageEdits.InvalidMessageEditsException expected) {
      // expected
    }
  }

  /** A raw newline in a subject is an email header-injection vector. */
  @Test
  public void stripsCarriageReturnAndLineFeedFromSubject() throws Exception {
    EmailMessageEdits edits = EmailMessageEdits.fromBody(new JSONObject(
        "{\"messageEdits\":{\"subject\":\"Asunto\\r\\nBcc: attacker@evil.com\"}}")).get();
    assertFalse(edits.getSubject().contains("\r"));
    assertFalse(edits.getSubject().contains("\n"));
    assertTrue(edits.getSubject().startsWith("Asunto"));
  }

  @Test
  public void escapesMarkupAndConvertsNewlinesInHtmlBody() throws Exception {
    EmailMessageEdits edits = EmailMessageEdits.fromBody(new JSONObject(
        "{\"messageEdits\":{\"message\":\"<b>a</b> & \\\"b\\\"\\nsegunda\"}}")).get();
    assertEquals("&lt;b&gt;a&lt;/b&gt; &amp; &quot;b&quot;<br>segunda", edits.toHtmlBody());
  }

  @Test
  public void htmlBodyIsNullWhenOnlySubjectWasEdited() throws Exception {
    EmailMessageEdits edits = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"subject\":\"Solo asunto\"}}")).get();
    assertNull(edits.toHtmlBody());
  }

  @Test
  public void contentHashIsStableAndDistinguishesContent() throws Exception {
    EmailMessageEdits first = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"message\":\"primer intento\"}}")).get();
    EmailMessageEdits same = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"message\":\"primer intento\"}}")).get();
    EmailMessageEdits other = EmailMessageEdits.fromBody(
        new JSONObject("{\"messageEdits\":{\"message\":\"texto corregido\"}}")).get();

    assertEquals(first.contentHash(), same.contentHash());
    assertNotEquals(first.contentHash(), other.contentHash());
  }

  private static String repeat(char character, int times) {
    StringBuilder builder = new StringBuilder(times);
    for (int i = 0; i < times; i++) {
      builder.append(character);
    }
    return builder.toString();
  }
}
