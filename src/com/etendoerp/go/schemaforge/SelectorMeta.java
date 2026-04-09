package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved target metadata for a selector.
 * Holds entity name, display/value properties, where clause,
 * and (for rich OBUISEL selectors) grid fields, searchable properties,
 * custom HQL, and auxiliary field descriptors.
 */
class SelectorMeta {
  final String entityName;
  final String displayProperty;
  final String whereClause;
  final boolean isRich;
  final boolean isCustomQuery;
  final String valueProperty;
  final List<RichFieldMeta> gridFields;
  final List<String> searchableProperties;
  final String customHql;
  final String entityAlias;
  final List<AuxFieldMeta> auxFields;

  /** Constructor for simple selectors (TableDir, Table, Search). */
  SelectorMeta(String entityName, String displayProperty, String whereClause) {
    this(entityName, displayProperty, whereClause,
        false, false, "id",
        new ArrayList<>(), new ArrayList<>(),
        null, "e", new ArrayList<>());
  }

  /** Constructor for rich (OBUISEL) selectors. */
  SelectorMeta(String entityName, String displayProperty, String whereClause,
      boolean isRich, boolean isCustomQuery, String valueProperty,
      List<RichFieldMeta> gridFields, List<String> searchableProperties,
      String customHql, String entityAlias, List<AuxFieldMeta> auxFields) {
    this.entityName = entityName;
    this.displayProperty = displayProperty;
    this.whereClause = whereClause;
    this.isRich = isRich;
    this.isCustomQuery = isCustomQuery;
    this.valueProperty = valueProperty;
    this.gridFields = gridFields;
    this.searchableProperties = searchableProperties;
    this.customHql = customHql;
    this.entityAlias = entityAlias;
    this.auxFields = auxFields;
  }
}
