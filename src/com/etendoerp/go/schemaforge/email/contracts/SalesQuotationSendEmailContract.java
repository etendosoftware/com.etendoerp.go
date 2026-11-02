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
 * Contract for sending sales quotation document notifications.
 */
public final class SalesQuotationSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "sales-quotation-send";

  /**
   * Creates the sales quotation send contract.
   *
   * @param documentResolver resolver for trusted sales quotation records
   */
  public SalesQuotationSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    super(NAME, "Sales Quotation", Objects.requireNonNull(documentResolver, "documentResolver"));
  }

  /**
   * A quotation is sent in any status but draft (ETP-6013), as its window offers "Enviar":
   * {@code showSend={Boolean(status) && status !== 'DR'}} in {@code QuotationSecondaryActions}.
   */
  @Override
  protected boolean isSendableStatus(String documentStatus) {
    return !STATUS_DRAFT.equals(documentStatus);
  }

  @Override
  protected String unsendableStatusMessageKey() {
    return MSG_NOT_FOR_DRAFT;
  }
}
