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
 * Contract for sending goods shipment (sales delivery) document notifications.
 */
public final class GoodsShipmentSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "goods-shipment-send";

  /**
   * Creates the goods shipment send contract.
   *
   * @param documentResolver resolver for trusted sales shipment records
   */
  public GoodsShipmentSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Goods Shipment", Objects.requireNonNull(documentResolver, "documentResolver"));
  }
}
