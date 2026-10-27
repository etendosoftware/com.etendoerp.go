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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The text of an {@code AD_MESSAGE} of this module in the request language, for the refusals NEO
 * raises itself (ETP-5840 exchange-rate rules, ETP-5473 financial-account country rules).
 *
 * <p><b>Why not {@code OBMessageUtils.messageBD}.</b> {@code com.etendoerp.go} is not a translation
 * module, so {@code export.database} never exports its {@code AD_MESSAGE_TRL} rows: on any other
 * environment the Spanish translation simply does not exist in the database, and {@code messageBD}
 * answers the English base text to a Spanish user — through REST and MCP, which have no SPA to
 * translate it afterwards. The Spanish text is therefore versioned in the
 * {@code messages/neo_hints_<lang>.properties} catalog.
 *
 * <p><b>Resolution order</b> (request language = {@link NeoLanguage#currentCode()}, the GO locale):
 * <ol>
 *   <li>an {@code AD_MESSAGE_TRL} row of that language marked {@code IsTranslated = 'Y'} — so an
 *       environment that does carry a translation keeps it (the untranslated copy Etendo creates
 *       for every system language is skipped);</li>
 *   <li>the catalog of that language, read through {@link NeoHints#catalogText};</li>
 *   <li>the {@code AD_MESSAGE} base text (English);</li>
 *   <li>the English catalog — so the text survives an environment whose {@code AD_MESSAGE} row has
 *       not been imported yet, or a request with no database at hand;</li>
 *   <li>the key itself.</li>
 * </ol>
 * {@code AD_MESSAGE} texts use {@code %0..%n} placeholders, the catalog {@code {0}..{n}}.
 *
 * <p><b>Identity.</b> {@link #error} also publishes the key in {@code error.messageKeys} (the
 * ETP-5175 structured identity), so a client renders its own wording by key instead of matching
 * the prose, which now changes with the request language.
 */
final class NeoMessageText {

  private static final Logger log = LogManager.getLogger(NeoMessageText.class);
  private static final String ENGLISH = "en";

  private NeoMessageText() {
  }

  /** {@link #text(String, String, Object...)} in the request language. */
  static String text(String key, Object... args) {
    return text(key, currentLanguage(), args);
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
    if (base != null) {
      return substitute(base, args);
    }
    String english = NeoHints.catalogText(key, ENGLISH, args);
    return english != null ? english : key;
  }

  /**
   * An error response whose {@code error.message} is the text of {@code key} in the request
   * language and whose {@code error.messageKeys} is {@code [key]}. The envelope is otherwise the
   * one {@link NeoResponse#error(int, String)} builds, so the HTTP status and the shape every
   * client already reads are unchanged.
   *
   * @param status the HTTP status code
   * @param key    the {@code AD_MESSAGE} value
   * @param args   the placeholder values, in order
   * @return the error response
   */
  static NeoResponse error(int status, String key, Object... args) {
    NeoResponse response = NeoResponse.error(status, text(key, args));
    JSONObject body = response.getBody();
    if (body != null) {
      try {
        body.getJSONObject("error").put(NeoProcessService.MESSAGE_KEYS, new JSONArray().put(key));
      } catch (Exception e) {
        // the plain error body is still a valid answer
        log.debug("Could not attach the message key {}: {}", key, e.getMessage());
      }
    }
    return response;
  }

  /** The request language, or {@code null} when there is no usable context. */
  private static String currentLanguage() {
    try {
      return NeoLanguage.currentCode();
    } catch (Exception e) {
      log.debug("No request language: {}", e.getMessage());
      return null;
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
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;
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
      log.debug("Could not read message text: {}", e.getMessage());
      return null;
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }
}
