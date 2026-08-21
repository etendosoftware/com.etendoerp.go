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

package com.etendoerp.go.rest;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

class TransactionalAuthEmailSender {

  private static final Logger log = LogManager.getLogger(TransactionalAuthEmailSender.class);

  private static final String CONTRACT_ENVIRONMENT_READY = "environment-ready";
  private static final String CONTRACT_NEW_ACCOUNT = "new-account";
  private static final String CONTRACT_PASSWORD_CHANGED = "password-changed";
  private static final String CONTRACT_RESET_PASSWORD = "reset-password";
  private static final String CONTRACT_COMPANY_INVITATION = "company-invitation";

  private final TransactionalEmailService emailService;

  TransactionalAuthEmailSender() {
    this(new TransactionalEmailService());
  }

  TransactionalAuthEmailSender(TransactionalEmailService emailService) {
    this.emailService = emailService;
  }

  boolean sendNewAccount(Account account) {
    return sendNewAccount(account, null);
  }

  boolean sendNewAccount(Account account, String language) {
    if (account == null) {
      return false;
    }
    return sendAccountLink(CONTRACT_NEW_ACCOUNT, account,
        EtendoGoAuthLinkBuilder.onboardingLink(), null, language);
  }

  boolean sendEnvironmentReady(Account account, String clientId) {
    return sendEnvironmentReady(account, clientId, null);
  }

  boolean sendEnvironmentReady(Account account, String clientId, String language) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account, clientId);
      addLanguageField(body, language);
      String dashboardLink = EtendoGoAuthLinkBuilder.dashboardLink();
      if (dashboardLink != null) {
        body.put(EmailContractCommandSupport.FIELD_LINK, dashboardLink);
      }
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID, clientId);
      return sendBestEffort(CONTRACT_ENVIRONMENT_READY, body);
    } catch (JSONException e) {
      log.warn("Could not build environment-ready email command", e);
      return false;
    }
  }

  boolean sendPasswordReset(Account account, String resetTokenHash, String resetLink) {
    if (account == null || StringUtils.isBlank(resetTokenHash) || StringUtils.isBlank(resetLink)) {
      return false;
    }
    return sendAccountLink(CONTRACT_RESET_PASSWORD, account, resetLink, resetTokenHash);
  }

  boolean sendCompanyInvitation(Invitation invitation, String inviteLink) {
    return sendCompanyInvitation(invitation, inviteLink, null);
  }

  boolean sendCompanyInvitation(Invitation invitation, String inviteLink, String language) {
    if (invitation == null || StringUtils.isBlank(inviteLink)) {
      return false;
    }
    try {
      JSONObject body = new JSONObject();
      body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID, invitation.getId());
      body.put(EmailContractCommandSupport.FIELD_TENANT_ID,
          invitation.getClient() != null ? invitation.getClient().getId() : "0");
      body.put(EmailContractCommandSupport.FIELD_LINK, inviteLink);
      addLanguageField(body, language);
      return sendBestEffort(CONTRACT_COMPANY_INVITATION, body);
    } catch (JSONException e) {
      log.warn("Could not build company-invitation email command", e);
      return false;
    }
  }

  boolean sendPasswordChanged(Account account) {
    return sendPasswordChanged(account, null);
  }

  boolean sendPasswordChanged(Account account, String language) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      addLanguageField(body, language);
      body.put(EmailContractCommandSupport.FIELD_DATE, Instant.now().toString());
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID,
          account.getId() + ":" + java.util.UUID.randomUUID());
      return sendBestEffort(CONTRACT_PASSWORD_CHANGED, body);
    } catch (JSONException e) {
      log.warn("Could not build password-changed email command", e);
      return false;
    }
  }

  private boolean sendAccountLink(String contractName, Account account, String link,
      String recordId) {
    return sendAccountLink(contractName, account, link, recordId, null);
  }

  private boolean sendAccountLink(String contractName, Account account, String link,
      String recordId, String language) {
    if (account == null || link == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      body.put(EmailContractCommandSupport.FIELD_LINK, link);
      addLanguageField(body, language);
      if (recordId != null) {
        body.put(EmailContractCommandSupport.FIELD_RECORD_ID, recordId);
      }
      return sendBestEffort(contractName, body);
    } catch (JSONException e) {
      log.warn("Could not build {} email command", contractName, e);
      return false;
    }
  }

  private static void addLanguageField(JSONObject body, String language) throws JSONException {
    String normalizedLanguage = StringUtils.trimToNull(language);
    if (normalizedLanguage != null) {
      body.put(EmailContractCommandSupport.FIELD_LANGUAGE, normalizedLanguage);
    }
  }

  private JSONObject baseCommand(Account account) throws JSONException {
    return baseCommand(account, account.getId());
  }

  private JSONObject baseCommand(Account account, String tenantId) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    body.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, account.getId());
    // Local account emails can be sent before an environment client exists; use the account id
    // as the fallback throttle partition to avoid a single global auth-email bucket.
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID,
        EmailContractCommandSupport.firstNonBlank(tenantId, account.getId()));
    return body;
  }

  private boolean sendBestEffort(String contractName, JSONObject body) {
    OBContext previousContext = OBContext.getOBContext();
    boolean adminModeSet = false;
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      adminModeSet = true;
      NeoResponse response = emailService.send(contractName, body);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      if (response != null && response.getHttpStatus() >= 400) {
        log.warn("Transactional auth email {} finished with HTTP {}", contractName,
            response.getHttpStatus());
        return false;
      }
      return response != null;
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("transactional auth email", e, log);
      log.warn("Transactional auth email {} failed after the account transaction was committed",
          contractName, e);
      return false;
    } finally {
      if (adminModeSet) {
        OBContext.restorePreviousMode();
      }
      OBContext.setOBContext(previousContext);
    }
  }
}
