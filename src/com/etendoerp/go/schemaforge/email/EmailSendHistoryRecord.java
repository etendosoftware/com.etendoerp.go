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
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * One readable per-document email send history entry, destined for
 * {@code ETGO_Email_Send_Log}.
 *
 * <p>Deliberately NOT the same thing as {@link EmailAuditRecord}, which feeds the anti-abuse
 * ledger {@code ETGO_Email_Safety}: that record hashes every recipient, carries no subject and
 * no body, and its rows are owned by client 0. Those properties are the point of the ledger
 * (see {@code DalEmailSafetyStoreTest}, which asserts the raw address is never persisted) and
 * are not relaxed. This record is the opposite by design — clear recipients, subject, the
 * operator's own message and the download link, owned by the sending tenant — so the operator can
 * read back what was sent from a document's own window.</p>
 *
 * <p>Everything here is already in hand by the time an audit event is recorded, because the
 * {@link EmailSendContext} is built before the first audit call site: the recipients in clear
 * come from {@link EmailSendContext#getRecipientSet()}; the subject and download link are still
 * verbatim in the provider request's template data (put there by
 * {@code DefaultDocumentSendEmailContract#buildTemplateData}, and until now discarded once the
 * gateway call returned); and the operator's message is re-read from the command body (see
 * {@link #operatorMessage(EmailSendContext)} for why the provider's rendered {@code body} is not
 * what gets stored).</p>
 */
public final class EmailSendHistoryRecord {

  /** Template-data key carrying the resolved subject. */
  static final String DATA_SUBJECT = "subject";

  /** Template-data key carrying the signed document download link. */
  static final String DATA_DOWNLOAD_LINK = "download_link";

  private final String contractName;
  private final String specName;
  private final String recordId;
  private final long sentAtMillis;
  private final String status;
  private final String errorMessage;
  private final List<String> recipientsTo;
  private final List<String> recipientsCc;
  private final String subject;
  private final String messageBody;
  private final String downloadLink;
  private final String language;
  private final String idempotencyKey;

  private EmailSendHistoryRecord(EmailSendContext context, EmailAuditRecord audit,
      String specName) {
    JSONObject data = context.getProviderRequest().getData();
    EmailRecipientSet recipients = context.getRecipientSet();
    this.contractName = audit.getContractName();
    this.specName = StringUtils.trimToNull(specName);
    this.recordId = audit.getRecordId();
    this.sentAtMillis = audit.getCreatedAtMillis();
    this.status = audit.getStatus();
    // The audit message is null on a successful send and carries the rejection reason
    // otherwise, which is exactly the ERROR_MESSAGE semantics of the history row.
    this.errorMessage = audit.getMessage();
    this.recipientsTo = recipients == null ? Collections.<String>emptyList() : recipients.getTo();
    this.recipientsCc = recipients == null ? Collections.<String>emptyList() : recipients.getCc();
    this.subject = optString(data, DATA_SUBJECT);
    this.messageBody = operatorMessage(context);
    this.downloadLink = optString(data, DATA_DOWNLOAD_LINK);
    this.language = commandLanguage(context);
    this.idempotencyKey = audit.getIdempotencyKey();
  }

  /**
   * Creates a history entry from the resolved send context and the audit event recorded for the
   * same attempt, so both rows always agree on status, message and timestamp.
   *
   * @param context resolved send context
   * @param audit audit record built for this same attempt
   * @param specName NEO spec the document belongs to, as declared by the contract
   * @return history entry ready to be persisted
   */
  public static EmailSendHistoryRecord create(EmailSendContext context, EmailAuditRecord audit,
      String specName) {
    Objects.requireNonNull(context, "EmailSendContext cannot be null");
    Objects.requireNonNull(audit, "EmailAuditRecord cannot be null");
    return new EmailSendHistoryRecord(context, audit, specName);
  }

  private static String optString(JSONObject data, String key) {
    return data == null ? null : StringUtils.trimToNull(data.optString(key));
  }

  /**
   * Returns the operator-authored message as they typed it, before HTML escaping.
   *
   * <p>Deliberately NOT the provider's {@code body} template value: that one is the whole
   * rendered email produced by {@code EmailLayout.render(...)} — a complete HTML document with
   * its own head and inline styles, several kilobytes wide and unreadable in a history panel.
   * What an operator wants to read back is the text they wrote, which is exactly what
   * {@link EmailMessageEdits#getMessage()} carries. A send that used the catalog's default copy
   * has no operator message at all and stores {@code null}; the subject is still recorded, and
   * the default copy is reproducible from the contract and the language.</p>
   *
   * <p>Parsing failures resolve to {@code null} rather than propagating:
   * {@code messageEdits} was already validated upstream by the time a send reaches an audit
   * point, and a malformed payload must never cost the send its history row.</p>
   */
  private static String operatorMessage(EmailSendContext context) {
    JSONObject body = context.getCommand() == null ? null : context.getCommand().getBody();
    try {
      return EmailMessageEdits.fromBody(body).map(EmailMessageEdits::getMessage).orElse(null);
    } catch (EmailMessageEdits.InvalidMessageEditsException e) {
      return null;
    }
  }

  private static String commandLanguage(EmailSendContext context) {
    JSONObject body = context.getCommand() == null ? null : context.getCommand().getBody();
    return body == null ? null
        : StringUtils.trimToNull(body.optString(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  /**
   * Returns the email contract name that produced this send.
   *
   * @return contract name
   */
  public String getContractName() {
    return contractName;
  }

  /**
   * Returns the NEO spec the document belongs to.
   *
   * @return spec name, or {@code null} when the contract does not declare one
   */
  public String getSpecName() {
    return specName;
  }

  /**
   * Returns the document record id this send belongs to.
   *
   * @return record id
   */
  public String getRecordId() {
    return recordId;
  }

  /**
   * Returns the instant the outcome was recorded.
   *
   * @return epoch milliseconds
   */
  public long getSentAtMillis() {
    return sentAtMillis;
  }

  /**
   * Returns the send outcome, one of {@code TransactionalEmailService}'s {@code STATUS_*} values.
   *
   * @return send status
   */
  public String getStatus() {
    return status;
  }

  /**
   * Returns why the attempt did not end in {@code SENT}.
   *
   * @return failure message, or {@code null} on a successful send
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * Returns the To recipients in clear.
   *
   * @return To addresses, never {@code null}
   */
  public List<String> getRecipientsTo() {
    return recipientsTo;
  }

  /**
   * Returns the CC recipients in clear.
   *
   * @return CC addresses, never {@code null}
   */
  public List<String> getRecipientsCc() {
    return recipientsCc;
  }

  /**
   * Returns the resolved subject.
   *
   * @return subject, or {@code null} when the provider data carried none
   */
  public String getSubject() {
    return subject;
  }

  /**
   * Returns the operator-authored message as raw text, before HTML escaping.
   *
   * @return message body, or {@code null} when the send used the contract's default copy
   */
  public String getMessageBody() {
    return messageBody;
  }

  /**
   * Returns the signed document download link included in the email.
   *
   * @return download link, or {@code null} when the email carried none
   */
  public String getDownloadLink() {
    return downloadLink;
  }

  /**
   * Returns the language the email was rendered in.
   *
   * @return AD language code, or {@code null} when the command carried none
   */
  public String getLanguage() {
    return language;
  }

  /**
   * Returns the resolved idempotency key for this attempt.
   *
   * @return idempotency key, or {@code null} when none was resolved
   */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
