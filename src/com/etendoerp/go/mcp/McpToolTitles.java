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

package com.etendoerp.go.mcp;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Human-readable tool titles advertised as {@code title} in {@code tools/list} (MCP 2025-06-18),
 * in the language of the user the MCP token belongs to.
 * <p>
 * Clients that support the field show it instead of the programmatic {@code name}, so a title
 * never exposes the internal {@code neo_} prefix. Clients that predate the field ignore it and keep
 * showing the name. Only the title is localized: the {@code description} is read by the model and
 * stays in English.
 * <ul>
 *   <li><b>Fixed tools</b> ({@code neo_list}, {@code docs}...): the
 *       {@code messages/mcp_titles_<language>.properties} catalog, English as fallback.</li>
 *   <li><b>Per-spec process and report tools</b>: the translated name of the spec's AD_Process,
 *       else of its AD_Window ({@link #fromSpec}), so no catalog entry is needed per spec.</li>
 *   <li>Anything else: the name humanized ({@code complete_order} → "Complete order").</li>
 * </ul>
 * The language is the user's Etendo language, not the client's: MCP does not carry the client
 * locale.
 */
final class McpToolTitles {

  private static final String BUNDLE = "com.etendoerp.go.mcp.messages.mcp_titles";
  private static final String KEY_PREFIX = "title.";
  /** The MCP surface is English (descriptions, errors), so an unknown language falls back to it. */
  private static final Locale FALLBACK_LOCALE = Locale.ENGLISH;
  /**
   * No-fallback control: the default {@code getBundle} consults the <b>JVM's</b> default locale
   * before giving up, so a host running under another locale would silently answer with it.
   */
  private static final ResourceBundle.Control NO_HOST_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
  private static final String INTERNAL_PREFIX = "neo_";

  private McpToolTitles() {
  }

  /**
   * Returns the title to advertise for a tool.
   *
   * @param tool the tool definition
   * @param language the user's Etendo language code, such as {@code es_ES}; blank means English
   * @return the explicit title the tool carries, else the catalog/humanized title for its name
   */
  static String resolve(McpToolDefinition tool, String language) {
    if (StringUtils.isNotBlank(tool.getTitle())) {
      return tool.getTitle();
    }
    return of(tool.getName(), language);
  }

  /**
   * Returns the display title for a tool name.
   *
   * @param toolName the programmatic MCP tool name
   * @param language the user's Etendo language code; blank means English
   * @return the catalog title for the language (English fallback), or the name humanized with any
   *     internal {@code neo_} prefix dropped; an empty string for a null or blank name
   */
  static String of(String toolName, String language) {
    if (StringUtils.isBlank(toolName)) {
      return "";
    }
    Locale locale = toLocale(language);
    String title = read(KEY_PREFIX + toolName, locale);
    if (title == null && !FALLBACK_LOCALE.getLanguage().equals(locale.getLanguage())) {
      title = read(KEY_PREFIX + toolName, FALLBACK_LOCALE);
    }
    return title != null ? title : humanize(toolName);
  }

  /**
   * Returns the translated AD name of a spec's process, else of its window.
   *
   * @param spec the process or report spec
   * @param language the user's language, or {@code null} for the base (untranslated) name
   * @return the name, or {@code null} when the spec links neither or the name is blank
   */
  static String fromSpec(SFSpec spec, Language language) {
    Process process = spec.getProcess();
    if (process != null) {
      String name = translatedName(process, Process.PROPERTY_NAME, language);
      if (name != null) {
        return name;
      }
    }
    Window window = spec.getADWindow();
    return window != null ? translatedName(window, Window.PROPERTY_NAME, language) : null;
  }

  private static String translatedName(BaseOBObject object, String property, Language language) {
    Object value = object.get(property, language, (String) object.getId());
    return value instanceof String && StringUtils.isNotBlank((String) value)
        ? ((String) value).trim()
        : null;
  }

  /**
   * Converts an Etendo language code into a {@link Locale}.
   *
   * @param language a code such as {@code es_ES} or {@code en_US}, may be blank
   * @return the matching locale, or English
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

  private static String read(String key, Locale locale) {
    try {
      return ResourceBundle.getBundle(BUNDLE, locale, NO_HOST_FALLBACK).getString(key);
    } catch (MissingResourceException e) {
      return null;
    }
  }

  private static String humanize(String toolName) {
    String base = toolName.startsWith(INTERNAL_PREFIX)
        ? toolName.substring(INTERNAL_PREFIX.length())
        : toolName;
    String words = base.replace('_', ' ').trim();
    if (words.isEmpty()) {
      return toolName;
    }
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
