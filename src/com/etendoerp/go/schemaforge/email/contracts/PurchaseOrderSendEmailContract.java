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
 * Contract for sending purchase order document notifications.
 */
public final class PurchaseOrderSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "purchase-order-send";

  /**
   * Creates the purchase order send contract.
   *
   * @param documentResolver resolver for trusted purchase order records
   */
  public PurchaseOrderSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Purchase Order", Objects.requireNonNull(documentResolver, "documentResolver"));
  }

}
