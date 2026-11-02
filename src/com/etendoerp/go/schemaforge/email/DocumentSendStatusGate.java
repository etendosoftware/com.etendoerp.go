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

import java.util.Optional;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The status gate of a document email (ETP-6013): why a document cannot be emailed in its current
 * status, worded in the request language. Extracted from {@link DefaultDocumentSendEmailContract},
 * which keeps the per-contract declarations ({@code isSendableStatus},
 * {@code unsendableStatusMessageKey}) and hands them in.
 */
final class DocumentSendStatusGate {

  private static final Logger log = LogManager.getLogger(DocumentSendStatusGate.class);

  private DocumentSendStatusGate() {
  }

  /**
   * The refusal of a send by status, if the document's status does not allow it.
   *
   * @param document          the resolved document
   * @param requestedLanguage the message language; blank falls back to the request language
   * @param sendable          the contract's rule: whether a status allows the send
   * @param messageKey        the contract's {@code AD_MESSAGE}, {@code {0}} the document number and
   *                          {@code {1}} the status name
   * @return the translated refusal, or empty when the status allows it or is unknown
   */
  static Optional<String> refusal(EmailDocumentRecord document, String requestedLanguage,
      Predicate<String> sendable, String messageKey) {
    String status = document.getDocumentStatus();
    if (status == null || sendable.test(status)) {
      return Optional.empty();
    }
    String language = StringUtils.isNotBlank(requestedLanguage) ? requestedLanguage
        : currentLanguage();
    return Optional.of(NeoMessageText.text(messageKey, language,
        StringUtils.defaultString(document.getDocumentNumber()),
        DocumentStatusLabel.of(status, language)));
  }

  /** The request language, or {@code null} without a usable context. */
  static String currentLanguage() {
    try {
      return NeoLanguage.currentCode();
    } catch (RuntimeException e) {
      log.debug("No request language for the email status refusal: {}", e.getMessage());
      return null;
    }
  }
}
