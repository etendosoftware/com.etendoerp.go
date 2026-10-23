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

/**
 * What the send modal prefills for one document, as the server composes it (ETP-5715).
 *
 * <p>The send modal shows a subject and a message the operator can edit, and posts whatever is on
 * screen back as {@code messageEdits}. The same two texts come from the contract's own message
 * catalog, so a caller that cannot render the modal (the MCP) sends exactly what the modal would
 * have shown. The base recipient is the contact the document would be addressed to when nobody
 * edits the list.</p>
 */
public final class EmailSendDefaults {

  private final String subject;
  private final String message;
  private final String baseRecipient;
  private final int maxRecipients;

  /**
   * Creates the defaults of one document.
   *
   * @param subject the prefilled subject
   * @param message the prefilled message, with its greeting, as plain text with {@code **bold**}
   * @param baseRecipient the document's own contact address, or {@code null} when it has none
   * @param maxRecipients most recipients, to and cc together, the contract accepts
   */
  public EmailSendDefaults(String subject, String message, String baseRecipient,
      int maxRecipients) {
    this.subject = subject;
    this.message = message;
    this.baseRecipient = baseRecipient;
    this.maxRecipients = maxRecipients;
  }

  /**
   * Returns the prefilled subject.
   *
   * @return the subject
   */
  public String getSubject() {
    return subject;
  }

  /**
   * Returns the prefilled message.
   *
   * @return the message, greeting included
   */
  public String getMessage() {
    return message;
  }

  /**
   * Returns the address the document would be sent to when the recipient list is not edited.
   *
   * @return the valid contact address, or {@code null} when the document has none
   */
  public String getBaseRecipient() {
    return baseRecipient;
  }

  /**
   * Returns the recipient cap of the contract.
   *
   * @return most recipients across to and cc
   */
  public int getMaxRecipients() {
    return maxRecipients;
  }
}
