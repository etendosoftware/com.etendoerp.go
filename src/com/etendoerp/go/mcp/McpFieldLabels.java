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

import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;

/**
 * Versioned field labels and descriptions for agents, in the user's language (ETP-5840, QA CP-12c).
 *
 * <p>{@code com.etendoerp.go} is not a translation module, so {@code export.database} never exports
 * its {@code AD_FIELD_TRL} rows: a field the module adds has no versioned Spanish label. This
 * catalog — {@code messages/mcp_field_labels_<language>.properties}, keyed by
 * {@code <TableName>.<ColumnName>} as AD writes them (the description under
 * {@code <TableName>.<ColumnName>.description}) — supplies it, the same way
 * {@code mcp_titles_<language>.properties} supplies the tool titles ({@link McpToolTitles}).
 *
 * <p>An entry wins over {@code AD_FIELD_TRL} and {@code AD_FIELD.name} / {@code .description}
 * ({@link McpSchemaFieldBuilder#loadFieldLabels}). There is no English fallback here: a language
 * with no entry answers {@code null} and the caller keeps its AD text, so an entry is only needed
 * where AD has no usable text. Generic: names no column, the catalog does.
 */
final class McpFieldLabels {

  private static final String BUNDLE = "com.etendoerp.go.mcp.messages.mcp_field_labels";
  private static final String DESCRIPTION_SUFFIX = ".description";
  /**
   * No-fallback control, as in {@link McpToolTitles}: the default {@code getBundle} consults the
   * JVM's default locale before giving up, so a host running under Spanish would answer every user
   * in Spanish.
   */
  private static final ResourceBundle.Control NO_HOST_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private McpFieldLabels() {
  }

  /**
   * The catalog label of a column in a language.
   *
   * @param tableName  the {@code AD_Table.TableName}, e.g. {@code C_Order}
   * @param columnName the {@code AD_Column.ColumnName}
   * @param language   the user's Etendo language code, e.g. {@code es_ES}; blank means English
   * @return the label, or {@code null} when the catalog has none. Total: never throws.
   */
  static String of(String tableName, String columnName, String language) {
    return read(tableName, columnName, "", language);
  }

  /**
   * The catalog description of a column in a language, keyed
   * {@code <TableName>.<ColumnName>.description}.
   *
   * @return the description, or {@code null} when the catalog has none. Total: never throws.
   */
  static String descriptionOf(String tableName, String columnName, String language) {
    return read(tableName, columnName, DESCRIPTION_SUFFIX, language);
  }

  private static String read(String tableName, String columnName, String suffix,
      String language) {
    if (StringUtils.isAnyBlank(tableName, columnName)) {
      return null;
    }
    try {
      String text = ResourceBundle.getBundle(BUNDLE, McpToolTitles.toLocale(language),
          NO_HOST_FALLBACK).getString(tableName + "." + columnName + suffix);
      return StringUtils.trimToNull(text);
    } catch (MissingResourceException | ClassCastException e) {
      return null;
    }
  }
}
