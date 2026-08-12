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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.poc.EmailInfo;

import com.etendoerp.email.spi.EmailSendContext;
import com.etendoerp.email.spi.EmailSender;
import com.etendoerp.go.schemaforge.email.ApiGatewayEmailProviderAdapter;
import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailProviderAdapter;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailProviderResponse;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;

/**
 * Routes core ERP emails through the Etendo GO provider gateway when no SMTP configuration
 * applies, delivering the piece ETP-4216 left out of scope.
 *
 * <p>Named after the provider rather than the concrete mail service: Etendo talks to an API
 * Gateway endpoint, and which service the gateway uses behind it can change without touching
 * this class.</p>
 *
 * <p>This sender is a <b>fallback, not an override</b>. Any environment that has an SMTP
 * configuration keeps using it, so installing this module changes nothing where email already
 * works. See {@code docs/plans/2026-08-10-go-provider-email-sender-design.md}.</p>
 */
@ApplicationScoped
public class GoProviderEmailSender implements EmailSender {

  /**
   * Ordering hint. Must stay strictly between {@code TbaiEmailSender}'s 100 and
   * {@code DefaultSmtpEmailSender}'s {@link Integer#MIN_VALUE}: below TicketBAI so that module
   * keeps delivering its own rejection alert through its own mailbox, above the SMTP floor so
   * this sender wins when SMTP does not apply. {@code com.smf.ticketbai} is not a dependency of
   * this module, so the bound is documented here rather than referenced in code.
   */
  static final int PRIORITY = 50;

  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";

  private final EmailProviderAdapter providerAdapter;

  /**
   * CDI constructor using the runtime-configured API Gateway adapter.
   */
  public GoProviderEmailSender() {
    this(new ApiGatewayEmailProviderAdapter());
  }

  /**
   * Test constructor accepting an explicit adapter.
   *
   * @param providerAdapter adapter used to submit the message
   */
  GoProviderEmailSender(EmailProviderAdapter providerAdapter) {
    this.providerAdapter = Objects.requireNonNull(providerAdapter,
        "Provider adapter cannot be null");
  }

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  /**
   * Reports this sender as eligible only when the provider is configured, no SMTP
   * configuration applies, and the message is fully representable by the provider payload.
   *
   * <p>A {@code null} email means the dispatcher is probing for capability rather than
   * selecting a transport, so the answer is "yes, this transport exists".</p>
   *
   * @param context the send context
   * @return {@code true} when this sender should carry the message
   */
  @Override
  public boolean isConfigured(EmailSendContext context) {
    if (!providerAdapter.isConfigured() || context == null) {
      return false;
    }
    // Fallback semantics: whenever SMTP applies, stay out of the way. The cascade has already
    // run in the caller, so the answer is in the context and needs no extra query.
    if (context.getResolvedSmtpConfig() != null || context.getSmtpConfig() != null) {
      return false;
    }
    EmailInfo email = context.getEmail();
    if (email == null) {
      return true;
    }
    // The provider payload has no attachment or BCC slot. Decline instead of dropping them,
    // so the dispatcher falls back to SMTP and nothing is lost silently.
    boolean hasAttachments = email.getAttachments() != null && !email.getAttachments().isEmpty();
    return !hasAttachments && StringUtils.isBlank(email.getRecipientBCC());
  }

  /**
   * Submits the message through the provider gateway using the bring-your-own-content
   * template. A non-successful provider response raises an exception: the dispatcher does not
   * retry through another transport, which is what keeps a transient gateway failure from
   * turning into a double send.
   *
   * @param context the send context carrying the resolved message
   * @throws Exception when the provider rejects the message or the transport fails
   */
  @Override
  public void send(EmailSendContext context) throws Exception {
    EmailInfo email = context.getEmail();
    if (email == null) {
      throw new OBException("No email to send in the provider send context");
    }
    JSONObject data = new JSONObject();
    data.put(FIELD_SUBJECT, email.getSubject());
    data.put(FIELD_BODY, email.getContent());

    List<String> to = splitAddresses(email.getRecipientTO());
    List<String> cc = providerAdapter.supportsCcChannel()
        ? splitAddresses(email.getRecipientCC())
        : new ArrayList<>();

    // CONTENT_TEMPLATE is the provider's bring-your-own-content template. Referenced rather
    // than duplicated as a literal so the two cannot drift apart.
    EmailProviderRequest request = new EmailProviderRequest(EmailRecipientSet.of(to, cc),
        DefaultDocumentSendEmailContract.CONTENT_TEMPLATE, data, email.getReplyTo());

    EmailProviderResponse response = providerAdapter.send(request);
    if (!response.isSuccessful()) {
      throw new OBException("Email provider rejected the message with status "
          + response.getStatusCode());
    }
  }

  /**
   * Splits a core address field into individual addresses. Core stores TO/CC/BCC as
   * comma-separated strings.
   *
   * @param addresses raw comma-separated address field, may be {@code null}
   * @return the individual non-blank addresses, never {@code null}
   */
  private static List<String> splitAddresses(String addresses) {
    List<String> result = new ArrayList<>();
    if (StringUtils.isBlank(addresses)) {
      return result;
    }
    for (String candidate : addresses.split(",")) {
      String trimmed = StringUtils.trimToNull(candidate);
      if (trimmed != null) {
        result.add(trimmed);
      }
    }
    return result;
  }
}
