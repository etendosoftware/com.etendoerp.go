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

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.businesspartner.BusinessPartner;

final class SalesDocumentEmailRecipientResolver {

  private SalesDocumentEmailRecipientResolver() {
  }

  static String resolveBusinessPartnerEmail(BusinessPartner businessPartner) {
    if (businessPartner == null) {
      return null;
    }
    String email = StringUtils.trimToNull(businessPartner.getEtgoEmail());
    if (email != null) {
      return email;
    }
    for (User user : businessPartner.getADUserList()) {
      if (Boolean.TRUE.equals(user.isActive()) && StringUtils.isNotBlank(user.getEmail())) {
        return user.getEmail();
      }
    }
    return null;
  }
}
