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

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Non-blocking hints attached to a NEO write response (ETP-5703): translated text, never an error,
 * never a status change.
 *
 * <p><b>Envelope.</b> The existing {@code messages} array — {@code [{type, text}]} — that
 * {@code BusinessPartnerHandler} (VIES result) puts on a save response and the callouts put on theirs
 * ({@code InvoiceCalloutHelper#appendMessage}); the SPA reads it as {@code data.messages}. On the
 * MCP channel it goes inside core's {@code response} wrapper instead of next to it, so
 * {@code McpToolRouterSupport#flattenCoreResponse} — which only flattens a body whose sole key is
 * {@code response} — still flattens it, and the agent reads {@code messages} at the top level of the
 * flat record response.
 *
 * <p><b>Catalog.</b> {@code messages/neo_hints_<lang>.properties}, English as the fallback, the same
 * bundle mechanism as {@code McpToolTitles} (no fallback to the JVM's locale).
 */
final class NeoHints {

  private static final Logger log = LogManager.getLogger(NeoHints.class);
  private static final String BUNDLE = "com.etendoerp.go.schemaforge.messages.neo_hints";
  private static final Locale FALLBACK_LOCALE = Locale.ENGLISH;
  private static final ResourceBundle.Control NO_HOST_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
  private static final String KEY_MESSAGES = "messages";
  private static final String KEY_RESPONSE = "response";

  /** Hint type, as the {@code messages} precedent spells it. */
  static final String TYPE_WARNING = "warning";

  private NeoHints() {
  }

  /** Whether {@code language} (an Etendo code such as {@code es_ES}) is a Spanish variant. */
  static boolean isSpanish(String language) {
    return StringUtils.startsWithIgnoreCase(language, "es");
  }

  /**
   * The catalog text of {@code key} in {@code language} (English when blank or unknown), with
   * {@code args} substituted; the key itself when the catalog lacks it.
   */
  static String text(String key, String language, Object... args) {
    Locale locale = StringUtils.isBlank(language) ? FALLBACK_LOCALE
        : Locale.forLanguageTag(language.replace('_', '-'));
    String pattern = read(key, locale);
    if (pattern == null) {
      pattern = read(key, FALLBACK_LOCALE);
    }
    if (pattern == null) {
      return key;
    }
    return new MessageFormat(pattern, locale).format(args);
  }

  private static String read(String key, Locale locale) {
    try {
      return ResourceBundle.getBundle(BUNDLE, new Locale(locale.getLanguage()), NO_HOST_FALLBACK)
          .getString(key);
    } catch (MissingResourceException e) {
      return null;
    }
  }

  /**
   * Appends {@code {type, text}} to the {@code messages} array of a write response body, at the
   * level the channel reads it (see the class javadoc). Total: never throws.
   *
   * @param body      the write response body, mutated in place
   * @param mcpOrigin whether the request came through the MCP layer
   */
  static void addMessage(JSONObject body, boolean mcpOrigin, String type, String text) {
    if (body == null) {
      return;
    }
    try {
      JSONObject inner = body.optJSONObject(KEY_RESPONSE);
      JSONObject target = mcpOrigin && inner != null ? inner : body;
      JSONArray messages = target.optJSONArray(KEY_MESSAGES);
      if (messages == null) {
        messages = new JSONArray();
        target.put(KEY_MESSAGES, messages);
      }
      messages.put(new JSONObject().put("type", type).put("text", text));
    } catch (Exception e) {
      log.debug("[ETP-5703] Could not add a hint to the write response: {}", e.getMessage());
    }
  }
}
