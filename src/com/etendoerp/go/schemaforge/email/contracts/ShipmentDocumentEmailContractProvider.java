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

import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractProvider;

import java.util.Arrays;
import java.util.Collection;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides {@code M_InOut} email contracts on both transaction sides: sales-side goods shipment
 * (outbound) and, since ETP-5124, Return Material Receipt (inbound sales return); and, also since
 * ETP-5124, the purchase-side Return to Vendor Shipment (inbound purchase return counterpart of a
 * Goods Receipt).
 */
@ApplicationScoped
public final class ShipmentDocumentEmailContractProvider implements EmailContractProvider {

  @Override
  public Collection<EmailContract> getContracts() {
    return Arrays.asList(
        new GoodsShipmentSendEmailContract(new DalShipmentEmailDocumentResolver()),
        new ReturnMaterialReceiptSendEmailContract(
            new DalReturnMaterialReceiptEmailDocumentResolver()),
        new ReturnToVendorShipmentSendEmailContract(
            new DalReturnToVendorShipmentEmailDocumentResolver()));
  }
}
