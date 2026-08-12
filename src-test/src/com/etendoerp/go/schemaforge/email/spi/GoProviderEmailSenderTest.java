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

package com.etendoerp.go.schemaforge.email.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.base.exception.OBException;
import org.openbravo.email.ResolvedSmtpConfig;
import org.openbravo.erpCommon.utility.poc.EmailInfo;
import org.openbravo.model.common.enterprise.EmailServerConfiguration;

import com.etendoerp.email.spi.EmailSendContext;
import com.etendoerp.go.schemaforge.email.EmailProviderAdapter;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailProviderResponse;

/**
 * Unit tests for {@link GoProviderEmailSender}: fallback selection semantics, the capability
 * probe, payload mapping and failure propagation.
 */
public class GoProviderEmailSenderTest {

  private static EmailProviderAdapter configuredAdapter() {
    EmailProviderAdapter adapter = mock(EmailProviderAdapter.class);
    when(adapter.isConfigured()).thenReturn(true);
    when(adapter.supportsMultipleRecipients()).thenReturn(true);
    when(adapter.supportsCcChannel()).thenReturn(true);
    return adapter;
  }

  private static EmailInfo.Builder emailBuilder() {
    return new EmailInfo.Builder()
        .setRecipientTO("alerts@example.com")
        .setSubject("Downloader failed")
        .setContent("<p>2 pairs failed</p>")
        .setContentType("text/html; charset=utf-8")
        .setSentDate(new Date());
  }

  private static EmailProviderRequest captureRequest(EmailProviderAdapter adapter, EmailInfo email)
      throws Exception {
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenReturn(new EmailProviderResponse(200, "{\"ok\":true}"));
    new GoProviderEmailSender(adapter).send(EmailSendContext.create(null, null, email));
    ArgumentCaptor<EmailProviderRequest> captor =
        ArgumentCaptor.forClass(EmailProviderRequest.class);
    verify(adapter).send(captor.capture());
    return captor.getValue();
  }

  @Test
  public void priorityStaysBetweenTbaiAndTheSmtpFloor() {
    // TbaiEmailSender's 100 is hardcoded here on purpose: com.smf.ticketbai is not a
    // dependency of this module, so the bound cannot be referenced.
    assertEquals(50, new GoProviderEmailSender(configuredAdapter()).getPriority());
    assertTrue(GoProviderEmailSender.PRIORITY < 100);
    assertTrue(GoProviderEmailSender.PRIORITY > Integer.MIN_VALUE);
  }

  @Test
  public void notConfiguredWhenProviderIsNotConfigured() {
    EmailProviderAdapter adapter = mock(EmailProviderAdapter.class);
    when(adapter.isConfigured()).thenReturn(false);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());
    assertFalse(new GoProviderEmailSender(adapter).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenContextIsNull() {
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(null));
  }

  @Test
  public void notConfiguredWhenCascadeResolvedAnSmtpConfig() {
    EmailSendContext context = EmailSendContext.create(null, mock(ResolvedSmtpConfig.class),
        emailBuilder().build());
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenAnSmtpServerRecordIsPresent() {
    EmailSendContext context = EmailSendContext.create(mock(EmailServerConfiguration.class), null,
        emailBuilder().build());
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenTheMessageCarriesAttachments() {
    EmailInfo email = emailBuilder()
        .setAttachments(Collections.singletonList(new File("/tmp/invoice.pdf")))
        .build();
    EmailSendContext context = EmailSendContext.create(null, null, email);
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void notConfiguredWhenTheMessageCarriesBcc() {
    EmailInfo email = emailBuilder().setRecipientBCC("audit@example.com").build();
    EmailSendContext context = EmailSendContext.create(null, null, email);
    assertFalse(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void configuredForTheCapabilityProbeWithNoEmail() {
    // EmailSenderDispatcher.hasAlternativeSenderConfigured() probes with a null email; callers
    // rely on this answer to get past their pre-send guard.
    EmailSendContext probe = EmailSendContext.create(null, null, null);
    assertTrue(new GoProviderEmailSender(configuredAdapter()).isConfigured(probe));
  }

  @Test
  public void configuredWhenNoSmtpAppliesAndTheMessageIsRepresentable() {
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());
    assertTrue(new GoProviderEmailSender(configuredAdapter()).isConfigured(context));
  }

  @Test
  public void sendMapsSubjectBodyAndRecipientsOntoTheCustomTemplate() throws Exception {
    EmailInfo email = emailBuilder()
        .setRecipientCC("copy@example.com")
        .setReplyTo("noreply@example.com")
        .build();

    EmailProviderRequest request = captureRequest(configuredAdapter(), email);

    assertEquals("custom", request.getTemplate());
    assertEquals(Collections.singletonList("alerts@example.com"), request.getRecipients().getTo());
    assertEquals(Collections.singletonList("copy@example.com"), request.getRecipients().getCc());
    assertEquals("noreply@example.com", request.getReplyTo());
    JSONObject data = request.getData();
    assertEquals("Downloader failed", data.getString("subject"));
    assertEquals("<p>2 pairs failed</p>", data.getString("body"));
  }

  @Test
  public void sendSplitsCommaSeparatedRecipients() throws Exception {
    EmailInfo email = emailBuilder()
        .setRecipientTO("one@example.com, two@example.com")
        .build();

    EmailProviderRequest request = captureRequest(configuredAdapter(), email);

    assertEquals(2, request.getRecipients().getTo().size());
    assertTrue(request.getRecipients().getTo().contains("one@example.com"));
    assertTrue(request.getRecipients().getTo().contains("two@example.com"));
  }

  @Test
  public void sendOmitsCcWhenTheProviderCannotDeliverIt() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.supportsCcChannel()).thenReturn(false);
    EmailInfo email = emailBuilder().setRecipientCC("copy@example.com").build();

    EmailProviderRequest request = captureRequest(adapter, email);

    assertTrue(request.getRecipients().getCc().isEmpty());
  }

  @Test
  public void sendLeavesReplyToUnsetWhenAbsent() throws Exception {
    EmailProviderRequest request = captureRequest(configuredAdapter(), emailBuilder().build());
    assertNull(request.getReplyTo());
  }

  @Test
  public void sendThrowsWhenTheProviderRejectsTheMessage() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenReturn(new EmailProviderResponse(502, "gateway error"));
    GoProviderEmailSender sender = new GoProviderEmailSender(adapter);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());

    try {
      sender.send(context);
      fail("Expected the provider rejection to surface as an exception");
    } catch (OBException expected) {
      assertTrue(expected.getMessage().contains("502"));
    }
  }

  @Test
  public void sendPropagatesTransportFailuresWithoutRetrying() throws Exception {
    EmailProviderAdapter adapter = configuredAdapter();
    when(adapter.send(any(EmailProviderRequest.class)))
        .thenThrow(new IOException("connection reset"));
    GoProviderEmailSender sender = new GoProviderEmailSender(adapter);
    EmailSendContext context = EmailSendContext.create(null, null, emailBuilder().build());

    try {
      sender.send(context);
      fail("Expected the transport failure to propagate without an SMTP retry");
    } catch (IOException expected) {
      assertEquals("connection reset", expected.getMessage());
    }
  }
}
