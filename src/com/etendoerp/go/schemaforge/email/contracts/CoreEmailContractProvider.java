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
  private static final String PROVIDER_TEMPLATE_CUSTOM = "custom";
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
        new AccountLinkEmailContract("reset-password", "reset-password", contractResolver,
            RESET_PASSWORD_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS),
        new AccountLinkEmailContract("new-account", PROVIDER_TEMPLATE_CUSTOM, contractResolver,
            NEW_ACCOUNT_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS, null,
            CoreEmailContractProvider::newAccountContent),
        new AccountLinkEmailContract("environment-ready", PROVIDER_TEMPLATE_CUSTOM, contractResolver,
            ENVIRONMENT_READY_RECIPIENT_THROTTLE_LIMIT, ACCOUNT_LINK_THROTTLE_WINDOW_SECONDS,
            DASHBOARD_LINK_PATH, CoreEmailContractProvider::environmentReadyContent),
        new AccountNoticeEmailContract("password-changed", PROVIDER_TEMPLATE_CUSTOM,
            contractResolver, CoreEmailContractProvider::passwordChangedContent),
        new LoginAlertEmailContract(contractResolver));
  }

  private static void newAccountContent(org.codehaus.jettison.json.JSONObject data,
      String language, String link) throws org.codehaus.jettison.json.JSONException {
    if ("es_ES".equals(language)) {
      data.put("subject", "Bienvenido a Etendo Go");
      data.put("body", "Tu cuenta de Etendo Go fue creada correctamente. "
          + "Abre este enlace para continuar: " + link);
      return;
    }
    data.put("subject", "Welcome to Etendo Go");
    data.put("body", "Your Etendo Go account was created successfully. "
        + "Open this link to continue: " + link);
  }

  private static void environmentReadyContent(org.codehaus.jettison.json.JSONObject data,
      String language, String link) throws org.codehaus.jettison.json.JSONException {
    data.put("subject", "Your Etendo Go environment is ready");
    data.put("body", "Your Etendo Go environment is ready. "
        + "Open this link to access your dashboard: " + link);
  }

  private static void passwordChangedContent(org.codehaus.jettison.json.JSONObject data)
      throws org.codehaus.jettison.json.JSONException {
    data.put("subject", "Your Etendo Go password was changed");
    data.put("body", "Your Etendo Go password was changed successfully. "
        + "If you did not make this change, contact support.");
  }
}
