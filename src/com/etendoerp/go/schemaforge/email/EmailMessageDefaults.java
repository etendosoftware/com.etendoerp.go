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
 * The subject and message a contract would use if the operator changes nothing (ETP-5003).
 *
 * <p>Exists so the send modal can show what will actually be sent instead of deriving its own
 * copy. The two used to be computed twice — once in JavaScript from the menu label, once in Java
 * from the message catalog — and they diverged, so an operator could read one subject on screen and
 * the customer receive another.</p>
 */
public final class EmailMessageDefaults {

  private final String subject;
  private final String message;

  /**
   * Creates the defaults.
   *
   * @param subject the default subject line
   * @param message the default message, as plain text the operator can edit
   */
  public EmailMessageDefaults(String subject, String message) {
    this.subject = subject;
    this.message = message;
  }

  /**
   * Default subject line.
   *
   * @return the subject
   */
  public String getSubject() {
    return subject;
  }

  /**
   * Default message, in the plain text the operator edits — not the rendered document. The layout
   * adds the greeting, the download button and the signature around it.
   *
   * @return the message
   */
  public String getMessage() {
    return message;
  }
}
