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

import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractDataResolver;
import com.etendoerp.go.schemaforge.email.EmailContractProvider;

import java.util.Arrays;
import java.util.Collection;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides non-document built-in email contracts.
 */
@ApplicationScoped
public final class CoreEmailContractProvider implements EmailContractProvider {

  private final EmailContractDataResolver contractResolver;
  private static final int RESET_PASSWORD_RECIPIENT_THROTTLE_LIMIT = 3;
  private static final int NEW_ACCOUNT_RECIPIENT_THROTTLE_LIMIT = 2;
  private static final int ENVIRONMENT_READY_RECIPIENT_THROTTLE_LIMIT = 2;
  private static final int ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS = 900;
  private static final String DASHBOARD_LINK_PATH = "dashboard";

  /**
   * Creates the provider with the default DAL-backed contact resolver.
   */
  public CoreEmailContractProvider() {
    this(new DalEmailContractDataResolver());
  }

  /**
   * Creates the provider with an explicit contract resolver.
   *
   * @param contractResolver resolver used by account and login alert contracts
   */
  public CoreEmailContractProvider(EmailContractDataResolver contractResolver) {
    this.contractResolver = contractResolver;
  }

  @Override
  public Collection<EmailContract> getContracts() {
    return Arrays.asList(
        new AccountLinkEmailContract("reset-password", contractResolver,
            RESET_PASSWORD_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS, null,
            "note.expiry", "note.ignore"),
        new AccountLinkEmailContract("new-account", contractResolver,
            NEW_ACCOUNT_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS),
        new AccountLinkEmailContract("environment-ready", contractResolver,
            ENVIRONMENT_READY_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS,
            DASHBOARD_LINK_PATH),
        new AccountNoticeEmailContract("password-changed", contractResolver, "note.warning"),
        new LoginAlertEmailContract(contractResolver),
        new CompanyInvitationEmailContract());
  }
}
