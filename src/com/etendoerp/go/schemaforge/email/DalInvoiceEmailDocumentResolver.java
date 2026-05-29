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
import org.openbravo.model.common.invoice.Invoice;

final class DalInvoiceEmailDocumentResolver implements EmailDocumentRecordResolver {

  private final String documentType;

  DalInvoiceEmailDocumentResolver(String documentType) {
    this.documentType = documentType;
  }

  @Override
  public Optional<EmailDocumentRecord> resolve(String recordId) {
    String normalizedId = StringUtils.trimToNull(recordId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    Invoice invoice = OBDal.getInstance().get(Invoice.class, normalizedId);
    if (invoice == null || !Boolean.TRUE.equals(invoice.isActive())
        || !DalEmailContractDataResolver.isReadableClient(invoice.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = invoice.getBusinessPartner();
    String recipientEmail = DalEmailContractDataResolver.resolveBusinessPartnerEmail(
        businessPartner);
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(new EmailDocumentRecord(recipientName, recipientEmail,
        invoice.getDocumentNo(),
        DalEmailContractDataResolver.formatAmount(invoice.getGrandTotalAmount(),
            invoice.getCurrency()),
        DalEmailContractDataResolver.buildDocumentDownloadLink(documentType, invoice.getId())));
  }
}
