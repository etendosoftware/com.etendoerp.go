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

package com.etendoerp.go.schemaforge.email;

import org.apache.commons.lang3.StringUtils;

final class EmailDocumentRecord {

  private final String recipientName;
  private final String recipientEmail;
  private final String documentNumber;
  private final String amount;
  private final String downloadLink;

  EmailDocumentRecord(String recipientName, String recipientEmail, String documentNumber,
      String amount, String downloadLink) {
    this.recipientName = StringUtils.trimToNull(recipientName);
    this.recipientEmail = StringUtils.trimToNull(recipientEmail);
    this.documentNumber = StringUtils.trimToNull(documentNumber);
    this.amount = StringUtils.trimToNull(amount);
    this.downloadLink = StringUtils.trimToNull(downloadLink);
  }

  String getRecipientName() {
    return recipientName;
  }

  String getRecipientEmail() {
    return recipientEmail;
  }

  String getDocumentNumber() {
    return documentNumber;
  }

  String getAmount() {
    return amount;
  }

  String getDownloadLink() {
    return downloadLink;
  }
}
