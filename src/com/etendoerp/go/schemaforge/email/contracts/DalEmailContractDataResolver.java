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

import com.etendoerp.go.schemaforge.email.EmailContactRecord;
import com.etendoerp.go.schemaforge.email.EmailContractDataResolver;

import java.math.BigDecimal;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.data.Account;

final class DalEmailContractDataResolver implements EmailContractDataResolver {

  private static final Logger log = LogManager.getLogger(DalEmailContractDataResolver.class);

  @Override
  public Optional<EmailContactRecord> findAccountContact(String accountId) {
    String normalizedId = StringUtils.trimToNull(accountId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Account account = OBDal.getInstance().get(Account.class, normalizedId);
    if (account == null || !Boolean.TRUE.equals(account.isActive())
        || !isReadableClient(account.getClient().getId())) {
      return Optional.empty();
    }
    return Optional.of(new EmailContactRecord(account.getName(), account.getEmail()));
  }

  @Override
  public Optional<EmailContactRecord> findUserContact(String userId) {
    String normalizedId = StringUtils.trimToNull(userId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    User user = OBDal.getInstance().get(User.class, normalizedId);
    if (user == null || !Boolean.TRUE.equals(user.isActive())
        || !isReadableClient(user.getClient().getId())) {
      return Optional.empty();
    }
    return Optional.of(new EmailContactRecord(user.getName(), user.getEmail()));
  }

  static String formatAmount(BigDecimal amount, Currency currency) {
    String value = amount == null ? "0" : amount.toPlainString();
    String isoCode = currency == null ? null : StringUtils.trimToNull(currency.getISOCode());
    return isoCode == null ? value : value + " " + isoCode;
  }

  static boolean isReadableClient(String clientId) {
    if (StringUtils.isBlank(clientId) || "0".equals(clientId)) {
      return true;
    }
    try {
      OBContext context = OBContext.getOBContext();
      if (clientId.equals(context.getCurrentClient().getId())) {
        return true;
      }
      for (String readableClient : context.getReadableClients()) {
        if (clientId.equals(readableClient)) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      log.debug("Could not resolve readable clients for email contract: {}", e.getMessage(), e);
      return false;
    }
  }
}
