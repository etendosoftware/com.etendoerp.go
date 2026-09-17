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
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Resolves trusted sales return receipt (Return Material Receipt) records for document email
 * contracts.
 *
 * <p>Both Goods Shipment and Return Material Receipt live on {@code M_InOut} with
 * {@code IsSOTrx = 'Y'} and the very same {@code MovementType} ({@code C-}) — the discriminator
 * is {@code C_DocType.IsReturn}, not the movement type (verified against a real instance; see
 * {@code artifacts/return-material-receipt/FINDINGS.md} in schema_forge). Filtering only on
 * {@link ShipmentInOut#isSalesTransaction()} the way {@link DalShipmentEmailDocumentResolver}
 * does would resolve a Goods Shipment id here too, so this resolver ALSO requires the document
 * type's {@code IsReturn} flag.
 *
 * <p>Like a shipment, a return receipt carries no monetary total, so the resolved record omits
 * the amount and the contract is configured without it.
 */
final class DalReturnMaterialReceiptEmailDocumentResolver implements EmailDocumentRecordResolver {

  DalReturnMaterialReceiptEmailDocumentResolver() {
  }

  @Override
  public Optional<EmailDocumentRecord> resolve(String recordId) {
    String normalizedId = StringUtils.trimToNull(recordId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, normalizedId);
    if (receipt == null || !Boolean.TRUE.equals(receipt.isActive())
        || !Boolean.TRUE.equals(receipt.isSalesTransaction())
        || !isReturnDocument(receipt)
        || receipt.getClient() == null
        || !DalEmailContractDataResolver.isReadableClient(receipt.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = receipt.getBusinessPartner();
    String recipientEmail = null;
    if (businessPartner != null) {
      recipientEmail = SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(
          businessPartner);
    }
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(EmailDocumentRecord.withGeneratedDownloadLink(recipientName,
        recipientEmail,
        receipt.getId(),
        receipt.getDocumentNo(),
        null,
        receipt.getClient().getId(),
        // No total: a return receipt carries no amount, same as a shipment.
        Collections.singletonList(
            EmailDocumentDetail.date("document.detail.movementDate", receipt.getMovementDate()))));
  }

  private static boolean isReturnDocument(ShipmentInOut receipt) {
    DocumentType documentType = receipt.getDocumentType();
    return documentType != null && Boolean.TRUE.equals(documentType.isReturn());
  }
}
