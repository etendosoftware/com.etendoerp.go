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

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The user-facing texts of the document exchange-rate rules (ETP-5840) and the refusals that carry
 * them.
 *
 * <p><b>Where a text comes from.</b> Every key is an {@code AD_MESSAGE} value of this module. The
 * text is resolved in the request language ({@link NeoLanguage#currentCode()}, the GO locale) in this
 * order:
 * <ol>
 *   <li>an {@code AD_MESSAGE_TRL} row of that language marked {@code IsTranslated = 'Y'} — so an
 *       environment that does carry a translation keeps it (an untranslated copy, which Etendo
 *       creates for every system language, is skipped);</li>
 *   <li>the versioned {@code messages/neo_hints_<lang>.properties} catalog, read through
 *       {@link NeoHints#catalogText} — the Spanish text lives here, because
 *       {@code com.etendoerp.go} is not a translation module and {@code export.database} never
 *       exports its {@code AD_MESSAGE_TRL} rows;</li>
 *   <li>the {@code AD_MESSAGE} base text (English), then the key itself.</li>
 * </ol>
 * {@code AD_MESSAGE} texts use {@code %0..%n} placeholders, the catalog {@code {0}..{n}}.
 *
 * <p><b>Identity.</b> Every refusal also carries {@code error.messageKeys} and
 * {@code error.messageParams} (the ETP-5175 structured identity), so a client may render its own
 * wording instead of the text. The text never names a property or a message key.
 */
final class DocumentRateMessages {

  private static final Logger log = LogManager.getLogger(DocumentRateMessages.class);

  /** Save refused: foreign currency and no rate. {@code %0} org ISO, {@code %1} document ISO,
   * {@code %2} reference date, {@code %3} reference date name. */
  static final String MSG_MISSING_ON_SAVE = "ETGO_ExchangeRateMissingOnSave";
  /** Completion refused: foreign currency and no rate. {@code %0} org ISO, {@code %1} doc ISO. */
  static final String MSG_MISSING_ON_COMPLETE = "ETGO_ExchangeRateMissingOnComplete";
  /** Name of the reference date of an invoice ({@code DateAcct}). */
  static final String LABEL_ACCOUNTING_DATE = "ETGO_RateRefDateAccounting";
  /** Name of the reference date of an order ({@code DateOrdered}). */
  static final String LABEL_ORDER_DATE = "ETGO_RateRefDateOrder";
  /** Name of the reference date of a quotation ({@code DateOrdered}). */
  static final String LABEL_QUOTATION_DATE = "ETGO_RateRefDateQuotation";

  static final int SC_UNPROCESSABLE = 422;
  static final String CODE_MISSING_ON_SAVE = "exchange_rate_missing";
  static final String CODE_MISSING_ON_COMPLETE = "exchange_rate_missing_on_complete";

  private static final String PARAM_ORG_CURRENCY = "orgCurrency";
  private static final String PARAM_DOC_CURRENCY = "documentCurrency";

  private DocumentRateMessages() {
  }

  /** {@link #text(String, String, Object...)} in the request language. */
  static String text(String key, Object... args) {
    return text(key, NeoLanguage.currentCode(), args);
  }

  /**
   * The text of {@code key} in {@code language}, resolved in the order the class documents.
   * Total: never throws.
   */
  static String text(String key, String language, Object... args) {
    String translated = translatedRow(key, language);
    if (translated != null) {
      return substitute(translated, args);
    }
    String catalog = NeoHints.catalogText(key, language, args);
    if (catalog != null) {
      return catalog;
    }
    String base = baseText(key);
    return base != null ? substitute(base, args) : key;
  }

  /**
   * The date as the language writes it: {@code dd/MM/yyyy} for Spanish, the locale's medium form
   * otherwise (never an ambiguous all-numeric form for English).
   */
  static String formatDate(LocalDate date, String language) {
    if (date == null) {
      return "";
    }
    if (NeoHints.isSpanish(language)) {
      return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
    Locale locale = StringUtils.isBlank(language) ? Locale.ENGLISH
        : Locale.forLanguageTag(language.replace('_', '-'));
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
  }

  /** The save-refusal text, in the request language. */
  static String missingOnSaveText(String orgIso, String docIso, LocalDate date,
      String referenceDateLabelKey) {
    String language = NeoLanguage.currentCode();
    return text(MSG_MISSING_ON_SAVE, language, orgIso, docIso, formatDate(date, language),
        text(referenceDateLabelKey, language));
  }

  /** The completion-refusal text, in the request language. */
  static String missingOnCompleteText(String orgIso, String docIso) {
    return text(MSG_MISSING_ON_COMPLETE, NeoLanguage.currentCode(), orgIso, docIso);
  }

  /**
   * 422 refusing a save of a foreign-currency document without a rate. {@code messageParams}:
   * {@code orgCurrency}, {@code documentCurrency} (ISO codes), {@code date} ({@code yyyy-MM-dd}),
   * {@code dateField} (the reference-date property, e.g. {@code accountingDate}) and
   * {@code referenceDate} (the {@code AD_MESSAGE} naming it).
   */
  static NeoResponse missingOnSave(String orgIso, String docIso, LocalDate date,
      String referenceDateLabelKey, String dateField) {
    JSONObject params = new JSONObject();
    try {
      params.put(PARAM_ORG_CURRENCY, orgIso);
      params.put(PARAM_DOC_CURRENCY, docIso);
      params.put("date", date != null ? date.toString() : JSONObject.NULL);
      params.put("dateField", dateField);
      params.put("referenceDate", referenceDateLabelKey);
    } catch (Exception e) {
      log.debug("[ETP-5840] Could not build the refusal params: {}", e.getMessage());
    }
    return refusal(CODE_MISSING_ON_SAVE,
        missingOnSaveText(orgIso, docIso, date, referenceDateLabelKey), params,
        MSG_MISSING_ON_SAVE, referenceDateLabelKey);
  }

  /**
   * 422 refusing to complete a foreign-currency document without a rate. {@code messageParams}:
   * {@code orgCurrency}, {@code documentCurrency} (ISO codes).
   */
  static NeoResponse missingOnComplete(String orgIso, String docIso) {
    JSONObject params = new JSONObject();
    try {
      params.put(PARAM_ORG_CURRENCY, orgIso);
      params.put(PARAM_DOC_CURRENCY, docIso);
    } catch (Exception e) {
      log.debug("[ETP-5840] Could not build the refusal params: {}", e.getMessage());
    }
    return refusal(CODE_MISSING_ON_COMPLETE, missingOnCompleteText(orgIso, docIso), params,
        MSG_MISSING_ON_COMPLETE);
  }

  private static NeoResponse refusal(String code, String message, JSONObject params,
      String... keys) {
    try {
      JSONObject error = new JSONObject();
      error.put("status", SC_UNPROCESSABLE);
      error.put("code", code);
      error.put("message", message);
      error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray(java.util.Arrays.asList(keys)));
      error.put(NeoProcessService.MESSAGE_PARAMS, params);
      return NeoResponse.error(SC_UNPROCESSABLE, new JSONObject().put("error", error));
    } catch (Exception e) {
      return NeoResponse.error(SC_UNPROCESSABLE, message);
    }
  }

  private static String substitute(String pattern, Object... args) {
    String result = pattern;
    if (args != null) {
      for (int i = args.length - 1; i >= 0; i--) {
        result = result.replace("%" + i, args[i] != null ? String.valueOf(args[i]) : "");
      }
    }
    return result;
  }

  /** The {@code IsTranslated = 'Y'} {@code AD_MESSAGE_TRL} text of {@code key}, or {@code null}. */
  private static String translatedRow(String key, String language) {
    if (StringUtils.isBlank(language)) {
      return null;
    }
    return queryText("SELECT t.msgtext FROM ad_message_trl t"
        + " JOIN ad_message m ON m.ad_message_id = t.ad_message_id"
        + " WHERE m.value = ? AND t.ad_language = ? AND t.isactive = 'Y' AND t.istranslated = 'Y'",
        key, language);
  }

  private static String baseText(String key) {
    return queryText("SELECT msgtext FROM ad_message WHERE value = ?", key);
  }

  private static String queryText(String sql, String... params) {
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        for (int i = 0; i < params.length; i++) {
          ps.setString(i + 1, params[i]);
        }
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? StringUtils.trimToNull(rs.getString(1)) : null;
        }
      }
    } catch (Exception e) {
      log.debug("[ETP-5840] Could not read message text: {}", e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
