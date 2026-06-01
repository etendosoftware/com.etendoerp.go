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

import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.order.Order;

/**
 * Resolves trusted sales order and quotation records for document email contracts.
 */
final class DalOrderEmailDocumentResolver implements EmailDocumentRecordResolver {

  private final SalesOrderDocumentFamily documentFamily;

  DalOrderEmailDocumentResolver() {
    this(SalesOrderDocumentFamily.SALES_ORDER);
  }

  DalOrderEmailDocumentResolver(SalesOrderDocumentFamily documentFamily) {
    this.documentFamily = documentFamily;
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
        || !acceptsDocumentSubtype(resolveDocumentSubtype(order))
        || order.getClient() == null
        || order.getCurrency() == null
        || !DalEmailContractDataResolver.isReadableClient(order.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = order.getBusinessPartner();
    String recipientEmail = null;
    if (businessPartner != null) {
      recipientEmail = SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(
          businessPartner);
    }
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(EmailDocumentRecord.withGeneratedDownloadLink(recipientName,
        recipientEmail,
        order.getId(),
        order.getDocumentNo(),
        DalEmailContractDataResolver.formatAmount(order.getGrandTotalAmount(),
            order.getCurrency()),
        order.getClient().getId()));
  }

  boolean acceptsDocumentSubtype(String documentSubtype) {
    return documentFamily.accepts(documentSubtype);
  }

  private static String resolveDocumentSubtype(Order order) {
    DocumentType documentType = order.getTransactionDocument();
    return documentType == null ? null : documentType.getSOSubType();
  }

  enum SalesOrderDocumentFamily {
    SALES_ORDER {
      @Override
      boolean accepts(String documentSubtype) {
        String normalizedSubtype = StringUtils.trimToEmpty(documentSubtype);
        return !QUOTATION_SUBTYPE.equals(normalizedSubtype)
            && !PROPOSAL_SUBTYPE.equals(normalizedSubtype);
      }
    },
    SALES_QUOTATION {
      @Override
      boolean accepts(String documentSubtype) {
        String normalizedSubtype = StringUtils.trimToEmpty(documentSubtype);
        return QUOTATION_SUBTYPE.equals(normalizedSubtype)
            || PROPOSAL_SUBTYPE.equals(normalizedSubtype);
      }
    };

    private static final String QUOTATION_SUBTYPE = "OB";
    private static final String PROPOSAL_SUBTYPE = "ON";

    abstract boolean accepts(String documentSubtype);
  }
}
