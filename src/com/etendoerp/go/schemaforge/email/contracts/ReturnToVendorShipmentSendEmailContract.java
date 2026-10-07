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

import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;

import java.util.Objects;

/**
 * Contract for sending Return to Vendor Shipment (purchase return shipment) document
 * notifications.
 *
 * <p>ETP-5124: this is the contract ETP-4717/ETP-4718 needed but never had. That earlier attempt
 * reused {@link ReturnToVendorSendEmailContract} (name {@code return-to-vendor-send}, wired for
 * the purchase-order-return {@code Order} family), but the frontend derives the contract name
 * generically as {@code `${windowName}-send`} — the window is
 * {@code return-to-vendor-shipment}, so the derived name is
 * {@code return-to-vendor-shipment-send}, which resolved to no registered contract. Every send
 * failed "Unknown email contract" and QA rejected the change. This contract's name follows the
 * {@code `${windowName}-send`} convention exactly, so
 * {@link DefaultDocumentSendEmailContract#getSpecName()} resolves the window correctly — unlike
 * {@code return-to-vendor-send}'s documented naming mismatch.
 */
public final class ReturnToVendorShipmentSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "return-to-vendor-shipment-send";

  /**
   * Creates the return to vendor shipment send contract.
   *
   * @param documentResolver resolver for trusted purchase return shipment records
   */
  public ReturnToVendorShipmentSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Return to Vendor Shipment",
        Objects.requireNonNull(documentResolver, "documentResolver"));
  }

}
