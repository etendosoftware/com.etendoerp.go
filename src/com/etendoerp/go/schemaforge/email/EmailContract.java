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
   * Validates the command and resolves the provider request from server-side
   * context.
   *
   * @param command contract command received by the executor
   * @param recipient recipient resolved before provider payload creation
   * @return resolved provider request or a contract-level rejection
   */
  EmailContractResolution resolve(EmailContractCommand command, EmailRecipientResolution recipient);
}
