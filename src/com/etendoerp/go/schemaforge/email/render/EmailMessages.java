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

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Localized copy for the shared email layout (ETP-5003).
 *
 * <p>Copy lives in {@code render/messages/emails_<language>.properties} inside the module rather
 * than in AD_Message: it needs no {@code export.database} step, no translation module, and it shows
 * up in the pull-request diff like any other file. The trade-off accepted is that a tenant cannot
 * edit this copy from Etendo, which is correct for platform-owned transactional mail.</p>
 *
 * <p>The language is always the <b>recipient's</b>, passed explicitly by the contract. It is never
 * read from {@code OBContext}: these emails are built while the sender's session is active, and the
 * recipient is frequently someone else entirely.</p>
 */
public final class EmailMessages {

  private static final Logger log = LogManager.getLogger(EmailMessages.class);
  private static final String BUNDLE = "com.etendoerp.go.schemaforge.email.render.messages.emails";
  /** Spanish is the fallback: the product's users are predominantly Spanish-speaking. */
  private static final Locale FALLBACK_LOCALE = new Locale("es", "ES");
  private static final ResourceBundle.Control NO_HOST_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private EmailMessages() {
  }

  /**
   * Resolves a message for a recipient language, interpolating positional parameters.
   *
   * <p>A missing key is never fatal: an email that renders with one odd-looking line still beats an
   * email that is never delivered, so the key itself is returned and the miss is logged.</p>
   *
   * @param key the message key
   * @param language the recipient language, such as {@code es_ES}; blank falls back to Spanish
   * @param params values for the {@code {0}}-style placeholders
   * @return the resolved message
   */
  public static String get(String key, String language, Object... params) {
    Locale locale = toLocale(language);
    String pattern = lookup(key, locale);
    if (params == null || params.length == 0) {
      return pattern;
    }
    return new MessageFormat(pattern, locale).format(params);
  }

  /**
   * Converts an Etendo language code into a {@link Locale}.
   *
   * @param language a code such as {@code es_ES} or {@code en_US}, may be blank
   * @return the matching locale, or the Spanish fallback
   */
  static Locale toLocale(String language) {
    String normalized = StringUtils.trimToNull(language);
    if (normalized == null) {
      return FALLBACK_LOCALE;
    }
    String[] parts = normalized.split("[_-]");
    if (parts.length >= 2) {
      return new Locale(parts[0], parts[1].toUpperCase(Locale.ROOT));
    }
    return new Locale(parts[0]);
  }

  /**
   * Reads one key, falling back to Spanish when the requested language has no bundle or no such
   * key.
   *
   * <p>The lookup deliberately uses a no-fallback {@link ResourceBundle.Control}: the default
   * {@code getBundle} behaviour consults the <b>JVM's</b> default locale before giving up, so a
   * server running under {@code en_US} would silently answer a Portuguese request in English. The
   * fallback has to be the product's, not the host's.</p>
   *
   * @param key the message key
   * @param locale the recipient locale
   * @return the message, or the key itself when it exists in no bundle
   */
  private static String lookup(String key, Locale locale) {
    String message = read(key, locale);
    if (message != null) {
      return message;
    }
    if (!FALLBACK_LOCALE.equals(locale)) {
      message = read(key, FALLBACK_LOCALE);
      if (message != null) {
        return message;
      }
    }
    log.warn("Missing email message key [{}] for locale [{}]", key, locale);
    return key;
  }

  private static String read(String key, Locale locale) {
    try {
      return ResourceBundle.getBundle(BUNDLE, locale, NO_HOST_FALLBACK).getString(key);
    } catch (MissingResourceException e) {
      return null;
    }
  }
}
