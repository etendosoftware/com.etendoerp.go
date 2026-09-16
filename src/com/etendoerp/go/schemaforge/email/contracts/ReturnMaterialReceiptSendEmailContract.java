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
 * Contract for sending Return Material Receipt (sales return receipt) document notifications.
 *
 * <p>ETP-5124: the window's {@code decisions.json} carried a {@code sendDocument.enabled: false}
 * flag disabling the "Enviar" button precisely because this contract did not exist yet. Its name
 * follows the standard {@code `${windowName}-send`} convention — the window is
 * {@code return-material-receipt}, so {@link DefaultDocumentSendEmailContract#getSpecName()}
 * resolves it correctly, unlike {@code return-to-vendor-send}'s documented naming mismatch.
 */
public final class ReturnMaterialReceiptSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "return-material-receipt-send";

  /**
   * Creates the return material receipt send contract.
   *
   * @param documentResolver resolver for trusted sales return receipt records
   */
  public ReturnMaterialReceiptSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Return Material Receipt", Objects.requireNonNull(documentResolver, "documentResolver"));
  }

}
