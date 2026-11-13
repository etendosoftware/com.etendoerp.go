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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.module.remittance.RemittanceType;

/**
 * ETP-6148 — the suggested name of a remittance, from its due date (the charge / credit date) and
 * its type: «Cobros noviembre 2026», «Pagos noviembre 2026», «Remesa noviembre 2026» when no type
 * is chosen yet; «Collections / Payments / Remittance November 2026» in English.
 *
 * <p>The words come from the {@code neo_hints} catalog
 * ({@value #KEY_COLLECTIONS}, {@value #KEY_PAYMENTS}, {@value #KEY_GENERIC}, with {@code {0}} the
 * month and {@code {1}} the year) in the request language, English as the fallback; the month name
 * comes from the JDK in the same language. Capped at {@value #NAME_MAX_LENGTH} characters, the
 * length of {@code REM_REMITTANCE.NAME}.
 *
 * <p>{@link #isSuggestion} tells a name this class could have produced — in any supported language,
 * for any kind, month and year — from one the user typed, so a suggestion is replaced when the date
 * or type changes and a typed name never is.
 *
 * <p>Plain static utility, no DAL: the type is passed in.
 */
final class RemittanceNameSuggestion {

  static final String KEY_COLLECTIONS = "ETGO_RemittanceNameCollections";
  static final String KEY_PAYMENTS = "ETGO_RemittanceNamePayments";
  static final String KEY_GENERIC = "ETGO_RemittanceNameGeneric";
  static final int NAME_MAX_LENGTH = 60;

  /** The languages whose suggestions {@link #isSuggestion} recognizes. */
  private static final List<String> LANGUAGES = List.of("es_ES", "en_US");
  private static final String MONTH_TOKEN = "\u0001";
  private static final String YEAR_TOKEN = "\u0002";
  private static final Pattern SUGGESTION = buildSuggestionPattern();

  private RemittanceNameSuggestion() {
  }

  /** Collection (pay-in) remittance, payment (pay-out) remittance, or not known yet. */
  enum Kind {
    COLLECTION(KEY_COLLECTIONS), PAYMENT(KEY_PAYMENTS), GENERIC(KEY_GENERIC);

    private final String key;

    Kind(String key) {
      this.key = key;
    }
  }

  /**
   * The kind of a remittance type: its payment method allows pay-in only → collection, pay-out
   * only → payment, otherwise (both, neither, no type) generic.
   *
   * @param type the remittance type, may be {@code null}
   * @return the kind
   */
  static Kind kindOf(RemittanceType type) {
    FIN_PaymentMethod method = type != null ? type.getPaymentMethod() : null;
    if (method == null) {
      return Kind.GENERIC;
    }
    boolean in = Boolean.TRUE.equals(method.isPayinAllow());
    boolean out = Boolean.TRUE.equals(method.isPayoutAllow());
    if (in && !out) {
      return Kind.COLLECTION;
    }
    if (out && !in) {
      return Kind.PAYMENT;
    }
    return Kind.GENERIC;
  }

  /**
   * The suggested name.
   *
   * @param dueDate  the remittance due date
   * @param kind     the kind of remittance
   * @param language the AD language code (e.g. {@code es_ES}); blank means English
   * @return the suggestion, at most {@value #NAME_MAX_LENGTH} characters
   */
  static String suggest(LocalDate dueDate, Kind kind, String language) {
    String month = dueDate.getMonth().getDisplayName(TextStyle.FULL, localeOf(language));
    String name = NeoHints.text(kind.key, language, month, String.valueOf(dueDate.getYear()));
    return StringUtils.left(name, NAME_MAX_LENGTH);
  }

  /**
   * Whether {@code name} has the shape of a suggestion: one of the catalog patterns, in a supported
   * language, with a month name of that language and a four-digit year.
   *
   * @param name the current name, may be {@code null}
   * @return {@code true} for a suggestion; {@code false} for a blank or typed name
   */
  static boolean isSuggestion(String name) {
    return StringUtils.isNotBlank(name) && SUGGESTION.matcher(name.trim()).matches();
  }

  /**
   * The calendar day at the start of {@code raw} ({@code yyyy-MM-dd…}), or {@code null}.
   *
   * @param raw a date or timestamp as the form or a request body carries it
   * @return the date, or {@code null} when blank or not a date
   */
  static LocalDate parseDay(String raw) {
    String value = StringUtils.trimToNull(raw);
    if (value == null || value.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(value.substring(0, 10));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Locale localeOf(String language) {
    return StringUtils.isBlank(language) ? Locale.ENGLISH
        : Locale.forLanguageTag(language.replace('_', '-'));
  }

  private static Pattern buildSuggestionPattern() {
    List<String> alternatives = new ArrayList<>();
    for (String language : LANGUAGES) {
      Locale locale = localeOf(language);
      List<String> months = new ArrayList<>();
      for (Month month : Month.values()) {
        months.add(Pattern.quote(month.getDisplayName(TextStyle.FULL, locale)));
      }
      String monthGroup = "(?:" + String.join("|", months) + ")";
      for (Kind kind : Kind.values()) {
        String shape = NeoHints.text(kind.key, language, MONTH_TOKEN, YEAR_TOKEN);
        StringBuilder regex = new StringBuilder();
        for (String part : shape.split("(?=[" + MONTH_TOKEN + YEAR_TOKEN + "])|(?<=["
            + MONTH_TOKEN + YEAR_TOKEN + "])")) {
          if (MONTH_TOKEN.equals(part)) {
            regex.append(monthGroup);
          } else if (YEAR_TOKEN.equals(part)) {
            regex.append("\\d{4}");
          } else if (!part.isEmpty()) {
            regex.append(Pattern.quote(part));
          }
        }
        alternatives.add(regex.toString());
      }
    }
    return Pattern.compile("(?:" + String.join("|", alternatives) + ")",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
