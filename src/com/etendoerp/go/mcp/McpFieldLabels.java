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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.FieldTrl;
import org.openbravo.model.ad.ui.Tab;

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
 *
 * <p>It also holds the per-field helpers {@link McpSchemaFieldBuilder#loadFieldLabels} composes to
 * apply that precedence: indexing the tab's fields, reading their {@code AD_FIELD_TRL} rows and
 * resolving each {@code {label, description}} pair.
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

  /**
   * Indexes the tab's active, column-backed fields by upper-cased DB column name, keeping the
   * first field per column (the tab's field list is sequence-ordered). The id of every kept field
   * is appended to {@code fieldIds}, in the same order, for the translation lookup.
   */
  static Map<String, Field> indexActiveFieldsByColumn(Tab adTab, List<String> fieldIds) {
    Map<String, Field> fieldByColumnName = new HashMap<>();
    for (Field field : adTab.getADFieldList()) {
      if (!Boolean.TRUE.equals(field.isActive()) || field.getColumn() == null) {
        continue;
      }
      String colName = field.getColumn().getDBColumnName().toUpperCase();
      if (fieldByColumnName.putIfAbsent(colName, field) == null) {
        fieldIds.add((String) field.getId());
      }
    }
    return fieldByColumnName;
  }

  /**
   * Resolves one field's {@code {label, description}} pair. Each value follows the precedence
   * documented on {@link McpSchemaFieldBuilder#loadFieldLabels}: this catalog for
   * {@code langCode}, then the {@code AD_FIELD_TRL} text ({@code trl}, may be {@code null}), then
   * the base-language {@code AD_FIELD} text.
   */
  static String[] resolveFieldLabel(Field field, String[] trl, String tableName,
      String langCode) {
    // ETP-5840: the versioned catalog wins for the fields whose translation is not versioned.
    String label = of(tableName, field.getColumn().getDBColumnName(), langCode);
    if (label == null) {
      label = translatedOrBase(trl, 0, field.getName());
    }
    String description = descriptionOf(tableName,
        field.getColumn().getDBColumnName(), langCode);
    if (description == null) {
      description = translatedOrBase(trl, 1, field.getDescription());
    }
    return new String[] { label, description };
  }

  /**
   * Returns {@code trl[index]} when a translation row exists and that value is not blank,
   * otherwise the base-language {@code base} text.
   */
  private static String translatedOrBase(String[] trl, int index, String base) {
    return trl != null && StringUtils.isNotBlank(trl[index]) ? trl[index] : base;
  }

  /**
   * Resolves the {@code {name, description}} translation of the given AD_Field ids into
   * {@code langCode} from {@code ADFieldTrl}. Runs in admin mode ({@code ADFieldTrl} is not
   * readable under a restricted NEO role) and never throws — an empty map means callers use the
   * base-language field text. Mirrors the {@code *_Trl} lookup convention in {@code NeoTrl}.
   */
  static Map<String, String[]> loadFieldTranslations(List<String> fieldIds,
      String langCode) {
    Map<String, String[]> byId = new HashMap<>();
    if (fieldIds.isEmpty() || StringUtils.isBlank(langCode)) {
      return byId;
    }
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;
      OBCriteria<FieldTrl> crit = OBDal.getInstance().createCriteria(FieldTrl.class);
      crit.add(Restrictions.in(FieldTrl.PROPERTY_FIELD + ".id", fieldIds));
      crit.add(Restrictions.eq(FieldTrl.PROPERTY_LANGUAGE + ".language", langCode));
      for (FieldTrl trl : crit.list()) {
        byId.put((String) trl.getField().getId(),
            new String[] { trl.getName(), trl.getDescription() });
      }
    } catch (Exception e) {
      // Translation table not readable / language missing → fall back to base-language labels.
      return new HashMap<>();
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
    return byId;
  }
}
