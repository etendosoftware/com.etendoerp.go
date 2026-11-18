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
package com.etendoerp.go.schemaforge.util;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * The {@code ETGO_Account} that owns a client (ETP-6228): the account of the user flagged as the
 * client's owner, not the account of whoever is signed in (an invited member signs in with their
 * own account). Used by the session payload and by the backend telemetry identity.
 */
public final class OwnerAccountResolver {

  private OwnerAccountResolver() {
    // static helper
  }

  /**
   * Returns the id of the account that owns {@code clientId}.
   *
   * @param clientId the {@code AD_Client_ID}
   * @return the {@code ETGO_Account} id, or {@code null} when the client has no owner flagged (a
   *     legacy client), the owner has no email or no active account matches: absent, never guessed
   */
  public static String findOwnerAccountId(String clientId) {
    OBContext.setAdminMode(true);
    try {
      return lookup(clientId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String lookup(String clientId) {
    String ownerUserId = OwnerSupport.findOwnerUserId(clientId);
    if (ownerUserId == null) {
      return null;
    }
    User owner = OBDal.getInstance().get(User.class, ownerUserId);
    String email = owner == null ? null : StringUtils.trimToNull(owner.getEmail());
    if (email == null) {
      return null;
    }
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where lower(account.email) = :email and account.active = true");
    query.setNamedParameter("email", email.toLowerCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    Account account = query.uniqueResult();
    return account == null ? null : account.getId();
  }
}
