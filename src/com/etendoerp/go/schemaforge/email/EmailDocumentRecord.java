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

/**
 * Document metadata used to resolve a transactional email recipient and payload.
 */
public final class EmailDocumentRecord {

  private final String recipientName;
  private final String recipientEmail;
  private final String documentNumber;
  private final String amount;
  private final String downloadLink;
  private final String clientId;

  /**
   * Creates a normalized document email record.
   *
   * @param recipientName display name for the server-resolved recipient
   * @param recipientEmail email address for the server-resolved recipient
   * @param documentNumber human-readable document number
   * @param amount formatted amount for templates that include totals
   * @param downloadLink absolute link used by the template
   * @param clientId trusted client id that owns the document
   */
  public EmailDocumentRecord(String recipientName, String recipientEmail, String documentNumber,
      String amount, String downloadLink, String clientId) {
    this.recipientName = StringUtils.trimToNull(recipientName);
    this.recipientEmail = StringUtils.trimToNull(recipientEmail);
    this.documentNumber = StringUtils.trimToNull(documentNumber);
    this.amount = StringUtils.trimToNull(amount);
    this.downloadLink = StringUtils.trimToNull(downloadLink);
    this.clientId = StringUtils.trimToNull(clientId);
  }

  public String getRecipientName() {
    return recipientName;
  }

  public String getRecipientEmail() {
    return recipientEmail;
  }

  public String getDocumentNumber() {
    return documentNumber;
  }

  public String getAmount() {
    return amount;
  }

  public String getDownloadLink() {
    return downloadLink;
  }

  public String getClientId() {
    return clientId;
  }
}
