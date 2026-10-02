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

import java.util.Collections;
import org.codehaus.jettison.json.JSONObject;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/** Registered for the shared executor, but authorizes only an unforgeable in-process body. */
public final class InternalAlertEmailContract implements EmailContract {
  public static final String NAME = "internal-alert";
  static final class TrustedBody extends JSONObject {
    private final InternalAlertEvent event;
    private final InternalAlertConfig config;
    private TrustedBody(InternalAlertEvent event, InternalAlertConfig config) {
      this.event = event;
      this.config = config;
      try {
        put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
        put("tenantId", event.getClientId() == null ? "0" : event.getClientId());
        put("recordId", event.getAttemptId());
        put("language", config.getLanguage());
      } catch (org.codehaus.jettison.json.JSONException e) {
        throw new IllegalStateException("Could not construct internal alert", e);
      }
    }
  }
  static JSONObject body(InternalAlertEvent event, InternalAlertConfig config) {
    return new TrustedBody(event, config);
  }
  @Override public String getName() { return NAME; }
  @Override public EmailAuthorizationResult authorize(EmailContractCommand command) {
    if (!(command.getBody() instanceof TrustedBody)) {
      return EmailAuthorizationResult.rejected(403, "Internal alerts require a server event");
    }
    TrustedBody body = (TrustedBody) command.getBody();
    return body.config.accepts(body.event.getStatus()) ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(403, "Internal alert result is disabled");
  }
  @Override public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    TrustedBody body = trusted(command);
    return EmailRecipientResolution.serverResolved(EmailRecipientSet.of(
        body.config.getRecipients(), Collections.emptyList()));
  }
  @Override public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    TrustedBody body = trusted(command);
    InternalAlertEvent event = body.event;
    String language = body.config.getLanguage();
    EmailContent content = EmailContent.builder()
        .paragraph(EmailMessages.get("internal-alert.body", language))
        .detail(EmailMessages.get("internal-alert.event", language), event.getEvent())
        .detail(EmailMessages.get("internal-alert.status", language), event.getStatus().name())
        .detail(EmailMessages.get("internal-alert.environment", language), event.getEnvironmentType())
        .detail(EmailMessages.get("internal-alert.attempt", language), event.getAttemptId())
        .detail(EmailMessages.get("internal-alert.client", language), event.getClientId())
        .detail(EmailMessages.get("internal-alert.path", language), event.getPath())
        .detail(EmailMessages.get("internal-alert.stage", language), event.getStage())
        .detail(EmailMessages.get("internal-alert.failure", language), event.getFailureCategory())
        .signature(EmailMessages.get("signature", language)).build();
    try {
      JSONObject data = new JSONObject();
      data.put("subject", EmailMessages.get("internal-alert.subject", language,
          event.getStatus().name(), event.getEnvironmentType(), event.getEvent()));
      data.put("body", EmailLayout.render(content));
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipientSet(),
          "custom", data, null));
    } catch (org.codehaus.jettison.json.JSONException e) {
      throw new IllegalStateException("Could not render internal alert", e);
    }
  }
  @Override public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest request) {
    InternalAlertEvent event = trusted(command).event;
    return EmailContractCommandSupport.deliveryPolicy(EmailContractCommandSupport.idempotencyKey(
        NAME, event.getEvent(), event.getAttemptId() + ":" + event.getStatus().name()),
        EmailThrottleRule.perRecipient(120, 900), EmailThrottleRule.global(500, 60));
  }
  private static TrustedBody trusted(EmailContractCommand command) {
    if (!(command.getBody() instanceof TrustedBody)) {
      throw new SecurityException("Internal alert body is not trusted");
    }
    return (TrustedBody) command.getBody();
  }
}
