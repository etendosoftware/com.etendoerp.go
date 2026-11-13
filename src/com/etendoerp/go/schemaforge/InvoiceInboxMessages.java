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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * ETP-6047 — the texts the invoice inbox upload (and, ETP-6049, the discard action) answer with. Each one is an {@code AD_MESSAGE}
 * (key below) translated through {@link OBMessageUtils#messageBD(String)}, with the English text
 * of the AD row as the fallback so an answer is never a bare key when the row is not installed yet.
 * The ETP-6049 discard keys and the ETP-6050 review key have no AD row on purpose: they resolve to
 * the English default here and the SPA translates by {@code error.code}.
 * {@code @name@} placeholders are replaced from the given values.
 */
final class InvoiceInboxMessages {

  static final String DISABLED = "ETGO_InvoiceInboxDisabled";
  static final String NOT_MULTIPART = "ETGO_InvoiceInboxNotMultipart";
  static final String NO_FILES = "ETGO_InvoiceInboxNoFiles";
  static final String TOO_MANY_FILES = "ETGO_InvoiceInboxTooManyFiles";
  static final String REQUEST_TOO_LARGE = "ETGO_InvoiceInboxRequestTooLarge";
  static final String UNSUPPORTED_TYPE = "ETGO_InvoiceInboxUnsupportedType";
  static final String EMPTY_FILE = "ETGO_InvoiceInboxEmptyFile";
  static final String STORE_FAILED = "ETGO_InvoiceInboxStoreFailed";
  private static final String FILE_NAME_PARAM = "fileName";
  static final String ORG_NOT_ALLOWED = "ETGO_InvoiceInboxOrgNotAllowed";
  static final String NOT_FOUND = "ETGO_InvoiceInboxNotFound";
  static final String NOT_DISCARDABLE = "ETGO_InvoiceInboxNotDiscardable";
  static final String DISCARD_ORG_NOT_ALLOWED = "ETGO_InvoiceInboxDiscardOrgNotAllowed";
  static final String PROCESSING = "ETGO_InvoiceInboxProcessing";

  private static final Map<String, String> DEFAULTS = Map.ofEntries(
      Map.entry(DISABLED, "The invoice inbox is not enabled for this user."),
      Map.entry(NOT_MULTIPART,
          "The request must be multipart/form-data with the files in the 'files' part."),
      Map.entry(NO_FILES,
          "No files were uploaded. Send between 1 and @max@ files in the 'files' part."),
      Map.entry(TOO_MANY_FILES, "Too many files: @count@ were sent and the maximum is @max@ per "
          + "upload. Nothing was uploaded."),
      Map.entry(REQUEST_TOO_LARGE, "The upload is too large. Each file can be at most @maxFileMB@ "
          + "MB and the whole upload at most @maxRequestMB@ MB."),
      Map.entry(UNSUPPORTED_TYPE,
          "'@fileName@' is not supported. Accepted types: PDF, JPG, JPEG and PNG."),
      Map.entry(EMPTY_FILE, "'@fileName@' is empty."),
      Map.entry(STORE_FAILED, "'@fileName@' could not be stored. Try again."),
      Map.entry(ORG_NOT_ALLOWED, "Documents cannot be uploaded to the current organization. "
          + "Select an organization you can write to."),
      Map.entry(NOT_FOUND, "The document was not found."),
      Map.entry(NOT_DISCARDABLE, "The document cannot be discarded in status @status@. Only "
          + "documents pending review, with errors or ready to confirm can be discarded."),
      Map.entry(DISCARD_ORG_NOT_ALLOWED,
          "The document belongs to an organization you cannot write to."),
      Map.entry(PROCESSING, "The document is still being read and cannot be opened yet."));

  private InvoiceInboxMessages() {
  }

  /**
   * The text of a message.
   *
   * @param key    the {@code AD_MESSAGE.VALUE}, one of the constants of this class
   * @param params placeholder name (without the {@code @}) and value pairs, as alternating
   *               arguments
   * @return the translated text with the placeholders replaced; never {@code null}
   */
  static String text(String key, String... params) {
    String text = null;
    try {
      text = OBMessageUtils.messageBD(key);
    } catch (RuntimeException e) {
      // no usable session or message cache: fall back to the English text
    }
    if (StringUtils.isBlank(text) || key.equals(text)) {
      text = DEFAULTS.get(key);
    }
    for (int i = 0; i + 1 < params.length; i += 2) {
      text = StringUtils.replace(text, "@" + params[i] + "@", neutralize(params[i + 1]));
    }
    return text;
  }

  /**
   * Values (file names are user input) never carry the {@code @} placeholder delimiter into a
   * message, so {@code @ETGO_X@.pdf} can not be read as an {@code AD_MESSAGE} token by whatever
   * translates the answer later. The delimiter becomes the full-width commercial at (U+FF20).
   */
  static String neutralize(String value) {
    return StringUtils.replace(StringUtils.defaultString(value), "@", "\uFF20");
  }

  static String unsupportedType(String fileName) {
    return text(UNSUPPORTED_TYPE, FILE_NAME_PARAM, fileName);
  }

  static String emptyFile(String fileName) {
    return text(EMPTY_FILE, FILE_NAME_PARAM, fileName);
  }

  static String storeFailed(String fileName) {
    return text(STORE_FAILED, FILE_NAME_PARAM, fileName);
  }
}
