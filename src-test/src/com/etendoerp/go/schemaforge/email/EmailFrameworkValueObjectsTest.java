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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Unit tests for transactional email framework value objects.
 */
public class EmailFrameworkValueObjectsTest {

  @Test
  public void documentRecordNormalizesBlankFields() {
    EmailDocumentRecord document = new EmailDocumentRecord(" Customer ", " customer@example.com ",
        " SO-1 ", " 10.00 USD ", " https://app.example.test/doc ", " tenant-1 ");
    EmailDocumentRecord blankRecord = new EmailDocumentRecord(" ", " ", " ", " ", " ", " ");

    assertEquals("Customer", document.getRecipientName());
    assertEquals("customer@example.com", document.getRecipientEmail());
    assertEquals("SO-1", document.getDocumentNumber());
    assertEquals("10.00 USD", document.getAmount());
    assertEquals("https://app.example.test/doc", document.getDownloadLink());
    assertEquals("tenant-1", document.getClientId());
    assertNull(blankRecord.getRecipientName());
    assertNull(blankRecord.getRecipientEmail());
    assertNull(blankRecord.getDocumentNumber());
    assertNull(blankRecord.getAmount());
    assertNull(blankRecord.getDownloadLink());
    assertNull(blankRecord.getClientId());
  }

  @Test
  public void providerRequestBuildsDefensivePayloadWithReplyTo() throws Exception {
    JSONObject data = new JSONObject();
    data.put("name", "Lucas");
    EmailProviderRequest request = new EmailProviderRequest(" user@example.com ",
        " reset-password ", data, " support@example.com ");

    JSONObject payload = request.toProviderPayload();
    payload.getJSONObject("data").put("name", "Changed");

    assertEquals("user@example.com", request.getRecipient());
    assertEquals("reset-password", request.getTemplate());
    assertEquals("support@example.com", request.getReplyTo());
    assertEquals("user@example.com", payload.getString("to"));
    assertEquals("reset-password", payload.getString("template"));
    assertEquals("support@example.com", payload.getString("replyTo"));
    assertEquals("Lucas", request.getData().getString("name"));
    assertNotSame(request.getData(), payload.getJSONObject("data"));
  }

  @Test
  public void providerRequestDefaultsMissingDataAndRejectsMissingMandatoryFields()
      throws Exception {
    EmailProviderRequest request = new EmailProviderRequest("user@example.com", "template", null,
        null);

    JSONObject payload = request.toProviderPayload();

    assertEquals(0, payload.getJSONObject("data").length());
    assertFalse(payload.has("replyTo"));
    assertNullPointerException(() -> new EmailProviderRequest(" ", "template", null, null));
    assertNullPointerException(() -> new EmailProviderRequest("user@example.com", " ", null, null));
  }

  @Test
  public void providerResponseClassifiesHttpStatusCodes() {
    assertTrue(new EmailProviderResponse(200, null).isSuccessful());
    assertTrue(new EmailProviderResponse(299, "{}").isSuccessful());
    assertFalse(new EmailProviderResponse(199, "{}").isSuccessful());
    assertFalse(new EmailProviderResponse(300, "{}").isSuccessful());
    assertEquals("", new EmailProviderResponse(202, null).getBody());
  }

  @Test
  public void recipientResolutionNormalizesSourcesAndRejectsBlankRecipients() {
    EmailRecipientResolution server = EmailRecipientResolution.serverResolved(
        " server@example.com ");
    EmailRecipientResolution caller = EmailRecipientResolution.callerProvided(
        " caller@example.com ");
    EmailRecipientResolution rejected = EmailRecipientResolution.rejected(404, "Missing");

    assertTrue(server.isResolved());
    assertEquals("server@example.com", server.getRecipient());
    assertEquals(EmailRecipientResolution.SOURCE_SERVER, server.getSource());
    assertFalse(server.isCallerProvided());
    assertTrue(caller.isCallerProvided());
    assertEquals("caller@example.com", caller.getRecipient());
    assertFalse(rejected.isResolved());
    assertEquals(404, rejected.getHttpStatus());
    assertEquals("Missing", rejected.getMessage());
    assertOBException(() -> EmailRecipientResolution.serverResolved(" "));
    assertOBException(() -> EmailRecipientResolution.callerProvided(null));
  }

  @Test
  public void authorizationAndResolutionExposeRejectionMetadata() {
    EmailAuthorizationResult allowed = EmailAuthorizationResult.allowed();
    EmailAuthorizationResult rejectedAuthorization = EmailAuthorizationResult.rejected(403,
        "Forbidden");
    EmailContractResolution rejectedResolution = EmailContractResolution.rejected(422,
        TransactionalEmailService.STATUS_VALIDATION_FAILED, "Bad command");

    assertTrue(allowed.isAllowed());
    assertEquals(200, allowed.getHttpStatus());
    assertNull(allowed.getMessage());
    assertFalse(rejectedAuthorization.isAllowed());
    assertEquals(403, rejectedAuthorization.getHttpStatus());
    assertEquals("Forbidden", rejectedAuthorization.getMessage());
    assertFalse(rejectedResolution.isReady());
    assertEquals(422, rejectedResolution.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED,
        rejectedResolution.getStatus());
    assertEquals("Bad command", rejectedResolution.getMessage());
    assertNull(rejectedResolution.getProviderRequest());
    assertNullPointerException(() -> EmailContractResolution.ready(null));
  }

  private static void assertNullPointerException(Runnable runnable) {
    try {
      runnable.run();
      fail("Expected NullPointerException");
    } catch (NullPointerException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }

  private static void assertOBException(Runnable runnable) {
    try {
      runnable.run();
      fail("Expected OBException");
    } catch (OBException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }
}
