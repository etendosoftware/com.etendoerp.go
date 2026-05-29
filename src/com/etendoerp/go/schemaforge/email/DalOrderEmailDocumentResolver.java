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

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.order.Order;

final class DalOrderEmailDocumentResolver implements EmailDocumentRecordResolver {

  private final String documentType;

  DalOrderEmailDocumentResolver(String documentType) {
    this.documentType = documentType;
  }

  @Override
  public Optional<EmailDocumentRecord> resolve(String recordId) {
    String normalizedId = StringUtils.trimToNull(recordId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Order order = OBDal.getInstance().get(Order.class, normalizedId);
    if (order == null || !Boolean.TRUE.equals(order.isActive())
        || !Boolean.TRUE.equals(order.isSalesTransaction())
        || !DalEmailContractDataResolver.isReadableClient(order.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = order.getBusinessPartner();
    String recipientEmail = DalEmailContractDataResolver.resolveBusinessPartnerEmail(
        businessPartner);
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(new EmailDocumentRecord(recipientName, recipientEmail,
        order.getDocumentNo(),
        DalEmailContractDataResolver.formatAmount(order.getGrandTotalAmount(),
            order.getCurrency()),
        DalEmailContractDataResolver.buildDocumentDownloadLink(documentType, order.getId())));
  }
}
