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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Formats the dates shown in the document summary block (ETP-5003).
 */
public final class EmailDates {

  private static final Logger log = LogManager.getLogger(EmailDates.class);
  static final String PATTERN_KEY = "document.detail.dateFormat";
  static final String DEFAULT_PATTERN = "dd/MM/yyyy";

  private EmailDates() {
  }

  /**
   * Formats a date in the recipient's language.
   *
   * <p>The pattern lives in the message catalog rather than being derived from the JDK's locale
   * data, so a reader can see exactly what each language prints and change it without touching
   * code. A missing or malformed pattern falls back to the Spanish default instead of failing the
   * send: no date format is worth refusing to deliver an invoice over.</p>
   *
   * @param value the date, or {@code null}
   * @param language the recipient's language
   * @return the formatted date, or {@code null} when there is no date
   */
  public static String format(Date value, String language) {
    if (value == null) {
      return null;
    }
    Locale locale = EmailMessages.toLocale(language);
    String pattern = EmailMessages.getOptional(PATTERN_KEY, language);
    try {
      return new SimpleDateFormat(pattern == null ? DEFAULT_PATTERN : pattern, locale)
          .format(value);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid email date pattern '{}' for language {}, using {}", pattern, language,
          DEFAULT_PATTERN, e);
      return new SimpleDateFormat(DEFAULT_PATTERN, locale).format(value);
    }
  }
}
