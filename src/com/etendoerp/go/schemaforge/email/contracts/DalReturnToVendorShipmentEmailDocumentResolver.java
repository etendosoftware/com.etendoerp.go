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
 * Resolves trusted purchase return shipment (Return to Vendor Shipment) records for document
 * email contracts.
 *
 * <p>This is the purchase-side counterpart of
 * {@link DalReturnMaterialReceiptEmailDocumentResolver}. Goods Receipt and Return to Vendor
 * Shipment both live on {@code M_InOut} with {@code IsSOTrx = 'N'} and the very same
 * {@code MovementType} — the discriminator is {@code C_DocType.IsReturn}, not the movement type.
 * Filtering only on {@link ShipmentInOut#isSalesTransaction()} being {@code false} the way a
 * purchase-side goods receipt resolver would, would resolve a Goods Receipt id here too, so this
 * resolver ALSO requires the document type's {@code IsReturn} flag.
 *
 * <p>Like a receipt, a return shipment carries no monetary total, so the resolved record omits
 * the amount and the contract is configured without it.
 */
final class DalReturnToVendorShipmentEmailDocumentResolver implements EmailDocumentRecordResolver {

  DalReturnToVendorShipmentEmailDocumentResolver() {
  }

  @Override
  public Optional<EmailDocumentRecord> resolve(String recordId) {
    String normalizedId = StringUtils.trimToNull(recordId);
    if (normalizedId == null) {
      return Optional.empty();
    }
    ShipmentInOut shipment = OBDal.getInstance().get(ShipmentInOut.class, normalizedId);
    if (shipment == null || !Boolean.TRUE.equals(shipment.isActive())
        || Boolean.TRUE.equals(shipment.isSalesTransaction())
        || !isReturnDocument(shipment)
        || shipment.getClient() == null
        || !DalEmailContractDataResolver.isReadableClient(shipment.getClient().getId())) {
      return Optional.empty();
    }
    BusinessPartner businessPartner = shipment.getBusinessPartner();
    String recipientEmail = null;
    if (businessPartner != null) {
      recipientEmail = SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(
          businessPartner);
    }
    String recipientName = businessPartner == null ? null : businessPartner.getName();
    return Optional.of(EmailDocumentRecord.withGeneratedDownloadLink(recipientName,
        recipientEmail,
        shipment.getId(),
        shipment.getDocumentNo(),
        null,
        shipment.getClient().getId(),
        // No total: a return shipment carries no amount, same as a receipt.
        Collections.singletonList(
            EmailDocumentDetail.date("document.detail.movementDate", shipment.getMovementDate()))));
  }

  private static boolean isReturnDocument(ShipmentInOut shipment) {
    DocumentType documentType = shipment.getDocumentType();
    return documentType != null && Boolean.TRUE.equals(documentType.isReturn());
  }
}
