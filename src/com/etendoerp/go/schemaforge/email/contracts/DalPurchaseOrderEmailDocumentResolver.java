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

import com.etendoerp.go.schemaforge.email.EmailDocumentDetail;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.order.Order;

/**
 * Resolves trusted purchase order and vendor-return records for document email contracts.
 *
 * <p>Mirrors {@link DalOrderEmailDocumentResolver} but scoped to the purchase side
 * ({@code isSalesTransaction() == false}), matching the purchase-order and return-to-vendor
 * window filters. The purchase family distinguishes regular purchase orders from vendor returns
 * through the transaction document {@code isReturn} flag.
 */
final class DalPurchaseOrderEmailDocumentResolver implements EmailDocumentRecordResolver {

  private final PurchaseOrderDocumentFamily documentFamily;

  DalPurchaseOrderEmailDocumentResolver() {
    this(PurchaseOrderDocumentFamily.PURCHASE_ORDER);
  }

  DalPurchaseOrderEmailDocumentResolver(PurchaseOrderDocumentFamily documentFamily) {
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
        || Boolean.TRUE.equals(order.isSalesTransaction())
        || !acceptsReturnDocument(isReturnDocument(order))
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
        order.getClient().getId(),
        buildDetails(order)));
  }

  /**
   * Builds the summary rows for this purchase document.
   *
   * <p>A purchase order shows none: it goes to a vendor, who reads it as an instruction rather than
   * as a record to check, and the summary would only repeat what the document itself states. With
   * no rows the contract skips the block entirely. A return carries its date, since that is the
   * fact the vendor has to match against the goods arriving back.</p>
   */
  private List<EmailDocumentDetail> buildDetails(Order order) {
    if (documentFamily != PurchaseOrderDocumentFamily.PURCHASE_RETURN) {
      return Collections.emptyList();
    }
    return Collections.singletonList(
        EmailDocumentDetail.date("document.detail.date", order.getOrderDate()));
  }

  boolean acceptsReturnDocument(boolean isReturnDocument) {
    return documentFamily.accepts(isReturnDocument);
  }

  private static boolean isReturnDocument(Order order) {
    DocumentType documentType = order.getTransactionDocument();
    return documentType != null && Boolean.TRUE.equals(documentType.isReturn());
  }

  enum PurchaseOrderDocumentFamily {
    PURCHASE_ORDER {
      @Override
      boolean accepts(boolean isReturnDocument) {
        return !isReturnDocument;
      }
    },
    PURCHASE_RETURN {
      @Override
      boolean accepts(boolean isReturnDocument) {
        return isReturnDocument;
      }
    };

    abstract boolean accepts(boolean isReturnDocument);
  }
}
