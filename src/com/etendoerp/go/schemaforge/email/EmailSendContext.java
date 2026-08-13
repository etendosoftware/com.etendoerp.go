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

import java.util.Locale;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * Trusted send context used by anti-abuse checks and audit records.
 */
public final class EmailSendContext {

  private static final String SYSTEM_CLIENT_ID = "0";

  private final EmailContractCommand command;
  private final EmailRecipientResolution recipient;
  private final EmailProviderRequest providerRequest;

  /**
   * Creates a context after contract authorization and recipient resolution.
   *
   * @param command contract command
   * @param recipient resolved recipient
   * @param providerRequest provider request
   */
  public EmailSendContext(EmailContractCommand command, EmailRecipientResolution recipient,
      EmailProviderRequest providerRequest) {
    this.command = Objects.requireNonNull(command, "Email contract command cannot be null");
    this.recipient = Objects.requireNonNull(recipient, "Email recipient cannot be null");
    this.providerRequest = Objects.requireNonNull(providerRequest,
        "Email provider request cannot be null");
  }

  /**
   * Returns the contract command.
   *
   * @return contract command
   */
  public EmailContractCommand getCommand() {
    return command;
  }

  /**
   * Returns the resolved recipient.
   *
   * @return recipient resolution
   */
  public EmailRecipientResolution getRecipient() {
    return recipient;
  }

  /**
   * Returns the provider request.
   *
   * @return provider request
   */
  public EmailProviderRequest getProviderRequest() {
    return providerRequest;
  }

  /**
   * Returns the contract name.
   *
   * @return contract name
   */
  public String getContractName() {
    return command.getContractName();
  }

  /**
   * Returns the tenant/client id from the trusted server context when available.
   *
   * @return tenant or client id
   */
  public String getTenantId() {
    String contextClientId = currentContextClientId();
    if (contextClientId != null && !SYSTEM_CLIENT_ID.equals(contextClientId)) {
      return contextClientId;
    }
    String commandTenantId = commandTenantId();
    if (commandTenantId != null) {
      return commandTenantId;
    }
    return contextClientId;
  }

  private String commandTenantId() {
    JSONObject body = command.getBody();
    if (body == null) {
      return null;
    }
    String tenantId = StringUtils.trimToNull(body.optString(
        EmailContractCommandSupport.FIELD_TENANT_ID));
    return tenantId == null ? StringUtils.trimToNull(body.optString(
        EmailContractCommandSupport.FIELD_CLIENT_ID))
        : tenantId;
  }

  private static String currentContextClientId() {
    try {
      OBContext context = OBContext.getOBContext();
      return context == null || context.getCurrentClient() == null
          ? null
          : StringUtils.trimToNull(context.getCurrentClient().getId());
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Returns the user id from the command when present.
   *
   * @return user id
   */
  public String getUserId() {
    JSONObject body = command.getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(
        EmailContractCommandSupport.FIELD_USER_ID));
  }

  /**
   * Returns the record id from the command when present.
   *
   * @return record id
   */
  public String getRecordId() {
    JSONObject body = command.getBody();
    return body == null ? null : StringUtils.trimToNull(body.optString(
        EmailContractCommandSupport.FIELD_RECORD_ID));
  }

  /**
   * Returns the provider template name.
   *
   * @return template name
   */
  public String getTemplate() {
    return providerRequest.getTemplate();
  }

  /**
   * Returns the destination email address.
   *
   * @return recipient email
   */
  public String getRecipientAddress() {
    return providerRequest.getRecipient();
  }

  /**
   * Returns the resolved multi-channel recipient set.
   *
   * @return recipient set carried by the provider request
   */
  public EmailRecipientSet getRecipientSet() {
    return providerRequest.getRecipients();
  }

  /**
   * Returns the lowercase recipient domain when available.
   *
   * @return recipient domain
   */
  public String getRecipientDomain() {
    String address = StringUtils.trimToNull(providerRequest.getRecipient());
    if (address == null) {
      return null;
    }
    int at = address.lastIndexOf('@');
    if (at < 0 || at == address.length() - 1) {
      return null;
    }
    return address.substring(at + 1).toLowerCase(Locale.ROOT);
  }
}
