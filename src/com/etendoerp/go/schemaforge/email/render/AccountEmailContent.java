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



package com.etendoerp.go.schemaforge.email.render;

import org.apache.commons.lang3.StringUtils;

/**
 * Builds the body of an account email from a message-key prefix (ETP-5003).
 *
 * <p>The account emails — welcome, environment ready, password reset, password changed, sign-in
 * alert, organization joined — are the same shape: a greeting, one paragraph, an optional call to action and up to two
 * fine-print lines. Rather than five near-identical builders, each contract names a key prefix and
 * this class assembles the blocks, taking whichever optional keys the catalog defines for it.</p>
 */
public final class AccountEmailContent {

  private AccountEmailContent() {
  }

  /**
   * Assembles the content for an account email with no fine print.
   *
   * @param keyPrefix message-key prefix, which is the contract name (for example
   *     {@code new-account})
   * @param language the recipient language
   * @param recipientName the recipient's name, may be blank
   * @param ctaUrl target of the call to action, or {@code null} for an email without a button
   * @param bodyParams values for the body's {@code {0}}-style placeholders
   * @return the assembled content
   */
  public static EmailContent build(String keyPrefix, String language, String recipientName,
      String ctaUrl, Object... bodyParams) {
    return buildWithNotes(keyPrefix, language, recipientName, ctaUrl, null, null, bodyParams);
  }

  /**
   * Assembles the content and appends fine-print lines, skipping the keys this email does not
   * define.
   *
   * @param keyPrefix message-key prefix
   * @param language the recipient language
   * @param recipientName the recipient's name, may be blank
   * @param ctaUrl target of the call to action, or {@code null}
   * @param notes note keys to append, relative to the prefix, such as {@code note.expiry}
   * @param noteParams values for the first note's placeholders
   * @param bodyParams values for the body's placeholders. <b>Callers must escape these</b> — the
   *     body is emitted as markup so that copy can emphasise a name, which means an unescaped
   *     value would be interpolated as HTML. Use {@link EmailEscape#escapeHtml(String)}.
   * @return the assembled content
   */
  public static EmailContent buildWithNotes(String keyPrefix, String language, String recipientName,
      String ctaUrl, String[] notes, Object[] noteParams, Object... bodyParams) {
    EmailContent.Builder content = EmailContent.builder();
    if (StringUtils.isNotBlank(recipientName)) {
      content.greetingHtml(EmailMessages.get(keyPrefix + ".greeting", language,
          "<strong>" + EmailEscape.escapeHtml(recipientName) + "</strong>"));
    }
    content.paragraphHtml(EmailMessages.get(keyPrefix + ".body", language, bodyParams));
    if (StringUtils.isNotBlank(ctaUrl)) {
      content.cta(EmailMessages.get(keyPrefix + ".cta", language), ctaUrl)
          .linkFallbackText(EmailMessages.get("link.fallback", language));
    }
    appendNotes(content, keyPrefix, language, notes, noteParams);
    content.signature(EmailMessages.get("signature", language));
    return content.build();
  }

  private static void appendNotes(EmailContent.Builder content, String keyPrefix, String language,
      String[] notes, Object[] noteParams) {
    if (notes == null) {
      return;
    }
    for (int i = 0; i < notes.length; i++) {
      Object[] params = i == 0 && noteParams != null ? noteParams : new Object[0];
      String note = EmailMessages.getOptional(keyPrefix + "." + notes[i], language, params);
      content.note(note);
    }
  }

  /**
   * Resolves the subject of an account email.
   *
   * @param keyPrefix message-key prefix
   * @param language the recipient language
   * @param params values for the subject's placeholders
   * @return the subject line
   */
  public static String subject(String keyPrefix, String language, Object... params) {
    return EmailMessages.get(keyPrefix + ".subject", language, params);
  }
}
