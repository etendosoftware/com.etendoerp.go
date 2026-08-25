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
import com.etendoerp.go.schemaforge.email.render.ValidityWindow;

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
  // ETP-4798: one more than reset-password. Asking for the confirmation link again is a normal
  // thing to do (the first mail lands in spam, the user mistypes nothing but waits), and the
  // account is blocked from creating its environment until it arrives.
  private static final int VERIFY_EMAIL_RECIPIENT_THROTTLE_LIMIT = 4;
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
            NEW_ACCOUNT_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS, null,
            ValidityWindow.Unit.HOURS, "note.expiry"),
        new AccountLinkEmailContract("environment-ready", contractResolver,
            ENVIRONMENT_READY_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS,
            DASHBOARD_LINK_PATH),
        new AccountLinkEmailContract("verify-email", contractResolver,
            VERIFY_EMAIL_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS, null,
            ValidityWindow.Unit.HOURS, "note.expiry", "note.ignore"),
        // An invited operator gets a welcome of its own: its button goes to the dashboard, not to
        // email verification, because an invitation is itself the proof that somebody meant to
        // reach this address (ETP-5003).
        new AccountLinkEmailContract("new-account-invitee", contractResolver,
            NEW_ACCOUNT_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS),
        new AccountNoticeEmailContract("password-changed", contractResolver, "note.warning"),
        new LoginAlertEmailContract(contractResolver),
        new OrganizationJoinedEmailContract(contractResolver),
        new CompanyInvitationEmailContract());
  }
}
