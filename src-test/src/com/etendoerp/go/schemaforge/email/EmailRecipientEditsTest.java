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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link EmailRecipientEdits}.
 */
public class EmailRecipientEditsTest {

  @Test
  public void absentFieldYieldsEmptyOptional() throws Exception {
    assertFalse(EmailRecipientEdits.fromBody(new JSONObject("{\"recordId\":\"r1\"}")).isPresent());
    assertFalse(EmailRecipientEdits.fromBody(null).isPresent());
  }

  @Test
  public void parsesChannelsFromCommandBody() throws Exception {
    JSONObject body = new JSONObject(
        "{\"recipientEdits\":{\"to\":{\"add\":[\"ap@x.com\"],\"remove\":[\"old@x.com\"]},"
        + "\"cc\":{\"add\":[\"pm@x.com\"]}}}");
    EmailRecipientEdits edits = EmailRecipientEdits.fromBody(body).get();
    assertEquals(Collections.singletonList("ap@x.com"), edits.getToAdd());
    assertEquals(Collections.singletonList("old@x.com"), edits.getToRemove());
    assertEquals(Collections.singletonList("pm@x.com"), edits.getCcAdd());
  }

  @Test
  public void rejectsInvalidEmailInAnyChannel() throws Exception {
    JSONObject body = new JSONObject(
        "{\"recipientEdits\":{\"cc\":{\"add\":[\"not-an-email\"]}}}");
    try {
      EmailRecipientEdits.fromBody(body);
      fail("expected rejection");
    } catch (EmailRecipientEdits.InvalidRecipientEditsException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }

  @Test
  public void rejectsEmptyAddress() throws Exception {
    JSONObject body = new JSONObject("{\"recipientEdits\":{\"to\":{\"add\":[\" \"]}}}");
    try {
      EmailRecipientEdits.fromBody(body);
      fail("expected rejection");
    } catch (EmailRecipientEdits.InvalidRecipientEditsException expected) {
      // expected
    }
  }

  @Test
  public void rejectsUnknownChannel() throws Exception {
    JSONObject body = new JSONObject("{\"recipientEdits\":{\"bcc\":{\"add\":[\"a@x.com\"]}}}");
    try {
      EmailRecipientEdits.fromBody(body);
      fail("expected rejection");
    } catch (EmailRecipientEdits.InvalidRecipientEditsException expected) {
      assertTrue(expected.getMessage().contains("channel"));
    }
  }

  @Test
  public void rejectsNonObjectShape() throws Exception {
    JSONObject body = new JSONObject("{\"recipientEdits\":\"oops\"}");
    try {
      EmailRecipientEdits.fromBody(body);
      fail("expected rejection");
    } catch (EmailRecipientEdits.InvalidRecipientEditsException expected) {
      // expected
    }
  }

  @Test
  public void applyToRemovesBaseAndAddsAcrossChannels() throws Exception {
    JSONObject body = new JSONObject(
        "{\"recipientEdits\":{\"to\":{\"add\":[\"ap@x.com\"],\"remove\":[\"Contact@X.com\"]},"
        + "\"cc\":{\"add\":[\"pm@x.com\"]}}}");
    EmailRecipientEdits edits = EmailRecipientEdits.fromBody(body).get();

    EmailRecipientSet result = edits.applyTo(Collections.singletonList("contact@x.com"));

    assertEquals(Collections.singletonList("ap@x.com"), result.getTo());
    assertEquals(Collections.singletonList("pm@x.com"), result.getCc());
  }

  @Test
  public void applyToDedupsCrossChannelWithToPrecedence() throws Exception {
    JSONObject body = new JSONObject(
        "{\"recipientEdits\":{\"cc\":{\"add\":[\"contact@x.com\",\"pm@x.com\"]}}}");
    EmailRecipientEdits edits = EmailRecipientEdits.fromBody(body).get();

    EmailRecipientSet result = edits.applyTo(Collections.singletonList("contact@x.com"));

    assertEquals(Collections.singletonList("contact@x.com"), result.getTo());
    assertEquals(Collections.singletonList("pm@x.com"), result.getCc());
  }
}
