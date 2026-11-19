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

import java.util.Optional;

/**
 * Server-side contract that translates an Etendo Go workflow command into a
 * provider-ready transactional email request.
 */
public interface EmailContract {

  /**
   * @return stable kebab-case contract name.
   */
  String getName();

  /**
   * Authorizes this contract command in the current server context.
   *
   * @param command contract command received by the executor
   * @return authorization result
   */
  EmailAuthorizationResult authorize(EmailContractCommand command);

  /**
   * Resolves the recipient before the provider request is created.
   *
   * @param command contract command received by the executor
   * @return recipient resolution
   */
  EmailRecipientResolution resolveRecipient(EmailContractCommand command);

  /**
   * Indicates whether this contract can use caller-provided recipients.
   *
   * @return {@code true} only for explicit support/admin recipient contracts
   */
  default boolean allowsCallerProvidedRecipients() {
    return false;
  }

  /**
   * Indicates whether every send attempt of this contract is recorded in the readable
   * per-document history ({@code ETGO_Email_Send_Log}).
   *
   * <p>Opt-in, and off by default on purpose. The history table stores recipients, subject and
   * the operator's message in clear so a document's window can show what was sent; that is the
   * right trade for a document a tenant's own operator emailed to a business partner, and the
   * wrong one for the account/auth family (invitation, reset password, login alert), whose
   * recipients are the platform's own users and whose copy carries single-use links. Those
   * contracts inherit {@code false} and never reach the table. The anti-abuse ledger
   * {@code ETGO_Email_Safety} is written for every contract either way, unchanged, with hashed
   * recipients and no copy.</p>
   *
   * @return {@code true} when send attempts must be written to the readable history
   */
  default boolean logsSendHistory() {
    return false;
  }

  /**
   * Returns the NEO spec (Schema Forge window) the documents of this contract belong to.
   *
   * <p>Recorded alongside each history row so the read endpoint can scope a lookup to one
   * window, and so the row can resolve the document's own {@code AD_Table}. Only meaningful for
   * contracts that {@link #logsSendHistory() log history}; everything else returns {@code null}.
   * </p>
   *
   * @return kebab-case spec name, or {@code null} when the contract is not window-scoped
   */
  default String getSpecName() {
    return null;
  }

  /**
   * Resolves anti-abuse policy for this send attempt.
   *
   * @param command contract command received by the executor
   * @param recipient recipient resolved before provider payload creation
   * @param providerRequest provider request resolved by the contract
   * @return delivery policy with idempotency and throttle rules
   */
  default EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    return EmailDeliveryPolicy.empty();
  }

  /**
   * Validates the command and resolves the provider request from server-side
   * context.
   *
   * @param command contract command received by the executor
   * @param recipient recipient resolved before provider payload creation
   * @return resolved provider request or a contract-level rejection
   */
  EmailContractResolution resolve(EmailContractCommand command, EmailRecipientResolution recipient);

  /**
   * The NEO spec a given send belongs to, for the history row.
   *
   * <p>Defaults to {@link #getSpecName()}. A contract that sends on behalf of documents of several
   * windows (the portal link renewal) answers per send.
   *
   * @param context the resolved send
   * @return kebab-case spec name, or {@code null}
   */
  default String getSpecName(EmailSendContext context) {
    return getSpecName();
  }

  /**
   * Called once the provider accepted the email, i.e. only for a {@code SENT} outcome, and never
   * for a duplicate, throttled, suppressed or failed one.
   *
   * <p>A no-op by default. Runs after the send's history and audit rows are written — and, for a
   * send with an idempotency key, already committed by the DAL safety store — so a failure here
   * can never take those rows back. What the contract writes here commits with the rest of the
   * request. It must not throw: the email has left and the send is reported as sent whatever
   * happens here (the service logs and swallows a failure).
   *
   * @param context the send that was delivered; {@code context.getProviderRequest()} carries the
   *     contract's own {@link EmailProviderRequest#getSendCorrelationId() correlation id}
   * @param sendLogId the {@code ETGO_Email_Send_Log} row written for it, or {@code null} when the
   *     contract keeps no history or the row could not be written
   */
  default void onDelivered(EmailSendContext context, String sendLogId) {
    // Nothing to do unless a contract needs to react to a delivered email.
  }

}
