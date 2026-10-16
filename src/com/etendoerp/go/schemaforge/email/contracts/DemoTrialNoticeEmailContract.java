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

package com.etendoerp.go.schemaforge.email.contracts;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.DemoTrialNotice;
import com.etendoerp.go.payment.DemoTrialNoticeRecord;
import com.etendoerp.go.payment.DemoTrialNoticeSource;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDeliveryPolicy;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.email.render.AccountEmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailDates;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;

/**
 * Tells a demo environment's owner that its trial is about to end, or has ended (ETP-5453).
 *
 * <p>The command carries only the environment ({@code clientId}). Whether a notice is owed, which
 * one, who receives it and in which language are all re-derived here from the environment data,
 * so the only thing a caller can choose is <em>which</em> environment to check — and the answer
 * is the notice the scheduled job would have sent anyway, at most once.</p>
 *
 * <p>Only a System-context caller is accepted: the scheduled job runs as System, while the
 * {@code /sws/neo/email-contracts} endpoint runs as the calling tenant user.</p>
 */
final class DemoTrialNoticeEmailContract implements EmailContract {

  static final String REMINDER_PREFIX = "demo-trial-reminder";
  static final String EXPIRED_PREFIX = "demo-trial-expired";
  static final String MESSAGE_SYSTEM_ONLY =
      "Demo trial notices are sent by the scheduled job only";
  static final String MESSAGE_NOT_OWED = "No demo trial notice is owed for this environment";
  private static final String UPGRADE_LINK_PATH = "upgrade";
  /** The provider's bring-your-own-content template: the layout is rendered here, not there. */
  private static final String CONTENT_TEMPLATE = "custom";
  private static final String SYSTEM_CLIENT = "0";

  private final DemoTrialNoticeSource source;
  private final BooleanSupplier systemCaller;
  private final Supplier<Instant> clock;

  DemoTrialNoticeEmailContract(DemoTrialNoticeSource source) {
    this(source, DemoTrialNoticeEmailContract::isSystemContext, Instant::now);
  }

  DemoTrialNoticeEmailContract(DemoTrialNoticeSource source, BooleanSupplier systemCaller,
      Supplier<Instant> clock) {
    this.source = source;
    this.systemCaller = systemCaller;
    this.clock = clock;
  }

  @Override
  public String getName() {
    return DemoTrialNotice.CONTRACT_NAME;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult editsRejection =
        EmailContractCommandSupport.rejectRecipientEditsIfPresent(command);
    if (!editsRejection.isAllowed()) {
      return editsRejection;
    }
    EmailAuthorizationResult messageRejection =
        EmailContractCommandSupport.rejectMessageEditsIfPresent(command);
    if (!messageRejection.isAllowed()) {
      return messageRejection;
    }
    if (!systemCaller.getAsBoolean()) {
      return EmailAuthorizationResult.rejected(403, MESSAGE_SYSTEM_ONLY);
    }
    EmailAuthorizationResult validation = EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_CLIENT_ID);
    if (!validation.isAllowed()) {
      return validation;
    }
    return findRecord(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(409, MESSAGE_NOT_OWED);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<DemoTrialNoticeRecord> owed = findRecord(command);
    if (!owed.isPresent()) {
      return EmailRecipientResolution.rejected(409, MESSAGE_NOT_OWED);
    }
    if (!EmailContractCommandSupport.isValidEmail(owed.get().getOwnerEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(owed.get().getOwnerEmail());
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (baseUrl == null) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Configured app base URL is required for this email contract");
    }
    Optional<DemoTrialNoticeRecord> owed = findRecord(command);
    if (!owed.isPresent()) {
      return EmailContractResolution.rejected(409,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, MESSAGE_NOT_OWED);
    }
    String link = PublicUrlResolver.appendPath(baseUrl, UPGRADE_LINK_PATH);
    DemoTrialNoticeRecord notice = owed.get();
    String language = notice.getLanguage();
    String prefix = notice.getNotice().getKind() == DemoTrialNotice.Kind.EXPIRED
        ? EXPIRED_PREFIX
        : REMINDER_PREFIX;
    String environmentName = StringUtils.defaultString(notice.getEnvironmentName());
    String trialEndDate = EmailDates.format(Date.from(notice.getNotice().getTrialEndsAt()),
        language);
    long remainingDays = notice.getNotice().getRemainingDays();
    try {
      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(notice.getOwnerName(), "User"));
      data.put("link", link);
      if (language != null) {
        data.put("language", language);
      }
      data.put("subject", AccountEmailContent.subject(prefix, language, environmentName,
          remainingDays));
      // The body is emitted as markup, so the one value we do not own is escaped here.
      data.put("body", EmailLayout.render(AccountEmailContent.build(prefix, language,
          notice.getOwnerName(), link,
          "<strong>" + EmailEscape.escapeHtml(environmentName) + "</strong>", trialEndDate,
          remainingDays)));
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          CONTENT_TEMPLATE, data, null));
    } catch (JSONException e) {
      throw new OBException("Could not build demo trial notice email payload", e);
    }
  }

  /**
   * De-duplicates on the environment, its trial start and the milestone.
   *
   * <p>The trial start is part of the key so that a trial restarted with a new start instant is
   * notified again, while a re-run, a restart or a retry of the same trial never is. There is no
   * per-domain ceiling: a daily run legitimately mails many owners on the same provider
   * (gmail.com), and a ceiling there would only push real notices to a later day.</p>
   */
  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String clientId = clientId(command);
    String recordId = findRecord(command)
        .map(owed -> clientId + ":" + owed.getNotice().getTrialStartedAt() + ":"
            + owed.getNotice().milestone())
        .orElse(clientId);
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(getName(), clientId, recordId),
        EmailThrottleRule.perTenant(5, 3600),
        EmailThrottleRule.perRecipient(20, 3600),
        EmailThrottleRule.global(500, 60));
  }

  private Optional<DemoTrialNoticeRecord> findRecord(EmailContractCommand command) {
    String clientId = clientId(command);
    if (StringUtils.isBlank(clientId)) {
      return Optional.empty();
    }
    return source.findOwedNotice(clientId, clock.get());
  }

  private static String clientId(EmailContractCommand command) {
    return EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_CLIENT_ID);
  }

  private static boolean isSystemContext() {
    try {
      OBContext context = OBContext.getOBContext();
      return context != null && context.getCurrentClient() != null
          && SYSTEM_CLIENT.equals(context.getCurrentClient().getId());
    } catch (RuntimeException e) {
      return false;
    }
  }
}
